package com.agentplatform.config;

import com.agentplatform.common.BusinessException;
import com.agentplatform.identity.PlatformPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class SecretService {
 private final JdbcTemplate jdbc; private final MasterKeyProperties props; private final SecureRandom random = new SecureRandom();
 public SecretService(JdbcTemplate jdbc, MasterKeyProperties props){this.jdbc=jdbc;this.props=props;}
 public UUID store(String value,String kind,PlatformPrincipal actor){
  if(value==null||value.isBlank()) return null;
  try{byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,nonce)); cipher.updateAAD(kind.getBytes(StandardCharsets.UTF_8));
   byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)); UUID id=UUID.randomUUID();
   jdbc.update("INSERT INTO secret_version(id,key_id,ciphertext,nonce,secret_kind,created_by,created_at) VALUES(?,?,?,?,?,?,?)",
    id.toString(),keyId(),encrypted,nonce,kind,actor.id().toString(),com.agentplatform.common.DatabaseTime.now()); return id;
  }catch(Exception e){throw new BusinessException(500,"SECRET_ENCRYPTION_FAILED","凭证加密失败");}
 }
 public String reveal(UUID id,String kind){
  if(id==null)return null;
  return jdbc.query("SELECT ciphertext,nonce,secret_kind FROM secret_version WHERE id=?",rs->{
   if(!rs.next())throw new BusinessException(404,"SECRET_NOT_FOUND","凭证不存在");
   if(!kind.equals(rs.getString("secret_kind")))throw new BusinessException(500,"SECRET_KIND_MISMATCH","凭证类型不匹配");
   try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,rs.getBytes("nonce")));cipher.updateAAD(kind.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(rs.getBytes("ciphertext")),StandardCharsets.UTF_8);}catch(Exception e){throw new BusinessException(500,"SECRET_DECRYPTION_FAILED","凭证解密失败");}
  },id.toString());
 }
 private SecretKeySpec key() throws Exception {String encoded=props.masterKey(); if((encoded==null||encoded.isBlank())&&props.masterKeyFile()!=null&&!props.masterKeyFile().isBlank()) encoded=Files.readString(Path.of(props.masterKeyFile())).trim();
  if(encoded==null||encoded.isBlank()) throw new BusinessException(503,"MASTER_KEY_UNAVAILABLE","密钥服务尚未配置"); byte[] raw=Base64.getDecoder().decode(encoded); if(raw.length!=32) throw new BusinessException(503,"MASTER_KEY_INVALID","主密钥必须是 Base64 编码的 32 字节密钥"); return new SecretKeySpec(raw,"AES");}
 private String keyId(){return props.keyId()==null||props.keyId().isBlank()?"local-v1":props.keyId();}
}
