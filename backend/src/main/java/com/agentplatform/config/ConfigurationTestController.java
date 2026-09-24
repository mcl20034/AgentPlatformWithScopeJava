package com.agentplatform.config;

import com.agentplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
public class ConfigurationTestController {
 private final JdbcTemplate jdbc; private final SecretService secrets; private final NetworkPolicyProperties network; private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
 public ConfigurationTestController(JdbcTemplate jdbc,SecretService secrets,NetworkPolicyProperties network){this.jdbc=jdbc;this.secrets=secrets;this.network=network;}
 @PostMapping("/models/{id}/tests") public Map<String,Object> testModel(@PathVariable UUID id){return testHttpConfig(id,false);}
 @PostMapping("/embedding-models/{id}/tests") public Map<String,Object> testEmbedding(@PathVariable UUID id){return testHttpConfig(id,true);}
 @PostMapping("/data-sources/{id}/tests") public Map<String,Object> testSource(@PathVariable UUID id){
  Map<String,Object> c=one("SELECT c.type,v.id version_id,v.endpoint,v.database_name,v.index_name,v.username,v.secret_version_id FROM data_source c JOIN data_source_version v ON v.id=c.active_version_id WHERE c.id=?",id);
  long start=System.nanoTime();try{
   if("MYSQL".equals(c.get("type"))){String url=String.valueOf(c.get("endpoint"));if(!url.startsWith("jdbc:mysql://"))throw new BusinessException(422,"ENDPOINT_INVALID","MySQL 地址必须使用 jdbc:mysql://");String host=URI.create(url.substring(5)).getHost();requireAllowed(host);String password=secret(c,"DATA_SOURCE");try(var conn=DriverManager.getConnection(url+(url.contains("?")?"&":"?")+"useSSL=false&connectTimeout=5000&socketTimeout=5000",String.valueOf(c.get("username")),password);var st=conn.createStatement()){conn.setReadOnly(true);try(var rs=st.executeQuery("SELECT 1")){if(!rs.next())throw new IllegalStateException("SELECT 1 未返回结果");}}}
   else{URI base=validatedHttp(String.valueOf(c.get("endpoint")));String index=String.valueOf(c.get("index_name"));HttpRequest.Builder b=HttpRequest.newBuilder(base.resolve("/"+index)).timeout(Duration.ofSeconds(8)).GET();addBasic(b,c);HttpResponse<Void> r=http.send(b.build(),HttpResponse.BodyHandlers.discarding());if(r.statusCode()/100!=2)throw new IllegalStateException("HTTP "+r.statusCode());}
   jdbc.update("UPDATE data_source_version SET test_status='PASSED',read_only_verified=TRUE WHERE id=?",String.valueOf(c.get("version_id")));return result(true,start,"连接测试通过");
  }catch(BusinessException e){failSource(c);throw e;}catch(Exception e){failSource(c);throw new BusinessException(502,"CONNECTION_TEST_FAILED","连接测试失败："+safe(e));}
 }
 private Map<String,Object> testHttpConfig(UUID id,boolean embedding){String ct=embedding?"embedding_config":"model_config",vt=embedding?"embedding_version":"model_version",fk=embedding?"embedding_id":"model_id",kind=embedding?"EMBEDDING":"MODEL";Map<String,Object> c=one("SELECT v.id version_id,v.provider,v.endpoint,v.secret_version_id FROM "+ct+" c JOIN "+vt+" v ON v.id=c.active_version_id WHERE c.id=?",id);long start=System.nanoTime();try{URI base=validatedHttp(String.valueOf(c.get("endpoint")));String path="OLLAMA".equals(c.get("provider"))?"/api/tags":"/models";URI target=URI.create(base.toString().replaceAll("/$","")+path);HttpRequest.Builder b=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(8)).GET();String key=secret(c,kind);if(key!=null)b.header("Authorization","Bearer "+key);HttpResponse<Void> r=http.send(b.build(),HttpResponse.BodyHandlers.discarding());if(r.statusCode()/100!=2)throw new IllegalStateException("HTTP "+r.statusCode());jdbc.update("UPDATE "+vt+" SET test_status='PASSED' WHERE id=?",String.valueOf(c.get("version_id")));return result(true,start,"连接和模型可用性验证通过");}catch(Exception e){jdbc.update("UPDATE "+vt+" SET test_status='FAILED' WHERE id=?",String.valueOf(c.get("version_id")));if(e instanceof BusinessException b)throw b;throw new BusinessException(502,"MODEL_TEST_FAILED","模型测试失败："+safe(e));}}
 private URI validatedHttp(String value){URI uri;try{uri=URI.create(value);}catch(Exception e){throw new BusinessException(422,"ENDPOINT_INVALID","服务地址格式不正确");}if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null)throw new BusinessException(422,"ENDPOINT_INVALID","服务地址必须是完整的 HTTP(S) 地址");requireAllowed(uri.getHost());return uri;}
 private void requireAllowed(String host){if(!network.allows(host))throw new BusinessException(422,"HOST_NOT_ALLOWED","目标主机不在部署白名单中");}
 private void addBasic(HttpRequest.Builder b,Map<String,Object> c){String u=(String)c.get("username"),p=secret(c,"DATA_SOURCE");if(u!=null&&!u.isBlank())b.header("Authorization","Basic "+Base64.getEncoder().encodeToString((u+":"+(p==null?"":p)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
 private String secret(Map<String,Object> c,String kind){Object id=c.get("secret_version_id");return id==null?null:secrets.reveal(UUID.fromString(String.valueOf(id)),kind);}
 private Map<String,Object> one(String sql,UUID id){return jdbc.query(sql,rs->{if(!rs.next())throw new BusinessException(404,"CONFIG_NOT_FOUND","配置不存在");var md=rs.getMetaData();Map<String,Object> m=new HashMap<>();for(int i=1;i<=md.getColumnCount();i++)m.put(md.getColumnLabel(i),rs.getObject(i));return m;},id.toString());}
 private void failSource(Map<String,Object> c){jdbc.update("UPDATE data_source_version SET test_status='FAILED',read_only_verified=FALSE WHERE id=?",String.valueOf(c.get("version_id")));}
 private static Map<String,Object> result(boolean ok,long start,String message){return Map.of("success",ok,"message",message,"elapsedMs",(System.nanoTime()-start)/1_000_000);}
 private static String safe(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m.replaceAll("(?i)(password|token|key)=[^&\\s]+","$1=***");}
}
