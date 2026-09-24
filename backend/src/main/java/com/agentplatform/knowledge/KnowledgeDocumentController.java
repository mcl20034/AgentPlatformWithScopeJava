package com.agentplatform.knowledge;

import com.agentplatform.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/v1/admin")
public class KnowledgeDocumentController {
 private final JdbcTemplate jdbc; private final StorageProperties storage; private final DocumentProcessor processor;
 public KnowledgeDocumentController(JdbcTemplate jdbc,StorageProperties storage,DocumentProcessor processor){this.jdbc=jdbc;this.storage=storage;this.processor=processor;}
 @GetMapping("/knowledge-bases/{kbId}/documents") public List<Map<String,Object>> list(@PathVariable UUID kbId){return jdbc.queryForList("SELECT d.id,d.normalized_name,v.id version_id,v.version_no,v.parse_status,v.failure_code,v.page_count,b.size_bytes,b.media_type,v.created_at FROM document d JOIN document_version v ON v.document_id=d.id JOIN blob_object b ON b.id=v.blob_id WHERE d.kb_id=? AND d.removed_at IS NULL AND v.version_no=(SELECT MAX(v2.version_no) FROM document_version v2 WHERE v2.document_id=d.id) ORDER BY v.created_at DESC",kbId.toString());}
 @PostMapping("/knowledge-bases/{kbId}/documents") @ResponseStatus(HttpStatus.ACCEPTED) @Transactional
 public Map<String,Object> upload(@PathVariable UUID kbId,@RequestPart("file") MultipartFile file)throws Exception{
  if(file.isEmpty())throw new BusinessException(422,"EMPTY_FILE","文件为空");if(file.getSize()>20L*1024*1024)throw new BusinessException(422,"FILE_TOO_LARGE","单文件不能超过 20 MB");
  String name=Path.of(Objects.requireNonNullElse(file.getOriginalFilename(),"document")).getFileName().toString();String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase():"";
  String media=switch(ext){case"pdf"->"application/pdf";case"docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";case"txt"->"text/plain";default->throw new BusinessException(422,"FILE_TYPE_UNSUPPORTED","只支持 PDF、DOCX 和 TXT");};
  Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE kb_id=? AND removed_at IS NULL",Integer.class,kbId.toString());if(count!=null&&count>=100)throw new BusinessException(422,"DOCUMENT_LIMIT","知识库最多 100 份有效文档");
  byte[] bytes=file.getBytes();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));Integer dup=jdbc.queryForObject("SELECT COUNT(*) FROM document d JOIN document_version v ON v.document_id=d.id WHERE d.kb_id=? AND d.removed_at IS NULL AND v.content_hash=?",Integer.class,kbId.toString(),hash);if(dup!=null&&dup>0)throw new BusinessException(409,"DUPLICATE_CONTENT","相同内容已存在");
  UUID docId;int version=1;List<String> existing=jdbc.query("SELECT id FROM document WHERE kb_id=? AND normalized_name=? AND removed_at IS NULL",(rs,n)->rs.getString(1),kbId.toString(),name.toLowerCase());
  if(existing.isEmpty()){docId=UUID.randomUUID();jdbc.update("INSERT INTO document(id,kb_id,normalized_name,created_at) VALUES(?,?,?,?)",docId.toString(),kbId.toString(),name.toLowerCase(),com.agentplatform.common.DatabaseTime.now());}else{docId=UUID.fromString(existing.getFirst());version=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM document_version WHERE document_id=?",Integer.class,docId.toString());}
  UUID blob=UUID.randomUUID(),ver=UUID.randomUUID(),job=UUID.randomUUID();String key="blobs/"+blob+"."+ext;Path target=Path.of(storage.root()).resolve(key).normalize();Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),"upload-",".tmp");Files.write(temp,bytes);Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);java.sql.Timestamp now=com.agentplatform.common.DatabaseTime.now();
  jdbc.update("INSERT INTO blob_object(id,storage_key,sha256,size_bytes,media_type,state,created_at) VALUES(?,?,?,?,?,'READY',?)",blob.toString(),key,hash,bytes.length,media,now);jdbc.update("INSERT INTO document_version(id,document_id,version_no,content_hash,blob_id,parse_status,created_at) VALUES(?,?,?,?,?,'QUEUED',?)",ver.toString(),docId.toString(),version,hash,blob.toString(),now);jdbc.update("INSERT INTO document_job(id,kb_id,document_version_id,kind,status,attempt,created_at,updated_at) VALUES(?,?,?,'PARSE','QUEUED',0,?,?)",job.toString(),kbId.toString(),ver.toString(),now,now);
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){processor.process(job,ver,key,media);}});return Map.of("documentId",docId,"versionId",ver,"jobId",job,"status","QUEUED");
 }
 @GetMapping("/document-jobs/{id}") public Map<String,Object> job(@PathVariable UUID id){return jdbc.queryForMap("SELECT id,kind,status,attempt,error_code,created_at,updated_at FROM document_job WHERE id=?",id.toString());}
 @PostMapping("/document-jobs/{id}/retry") public void retry(@PathVariable UUID id){Map<String,Object> j=jdbc.queryForMap("SELECT j.document_version_id,b.storage_key,b.media_type FROM document_job j JOIN document_version v ON v.id=j.document_version_id JOIN blob_object b ON b.id=v.blob_id WHERE j.id=? AND j.status='FAILED'",id.toString());processor.process(id,UUID.fromString(String.valueOf(j.get("document_version_id"))),String.valueOf(j.get("storage_key")),String.valueOf(j.get("media_type")));}
 @DeleteMapping("/documents/{id}") public void remove(@PathVariable UUID id){jdbc.update("UPDATE document SET removed_at=? WHERE id=?",com.agentplatform.common.DatabaseTime.now(),id.toString());}
}
