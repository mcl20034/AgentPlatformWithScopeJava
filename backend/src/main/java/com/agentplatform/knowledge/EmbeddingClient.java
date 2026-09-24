package com.agentplatform.knowledge;

import com.agentplatform.common.BusinessException;
import com.agentplatform.config.SecretService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class EmbeddingClient {
 private final JdbcTemplate jdbc;private final SecretService secrets;private final ObjectMapper json;private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
 public EmbeddingClient(JdbcTemplate jdbc,SecretService secrets,ObjectMapper json){this.jdbc=jdbc;this.secrets=secrets;this.json=json;}
 public List<float[]> embed(UUID embeddingId,List<String> input){Config c=config(embeddingId);try{boolean ollama="OLLAMA".equals(c.provider);URI target=URI.create(c.endpoint.replaceAll("/$","")+(ollama?"/api/embed":"/embeddings"));Map<String,Object> body=Map.of("model",c.model,"input",input);HttpRequest.Builder request=HttpRequest.newBuilder(target).timeout(Duration.ofMillis(c.timeout)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));if(c.secretId!=null){String key=secrets.reveal(UUID.fromString(c.secretId),"EMBEDDING");if(key!=null&&!key.isBlank())request.header("Authorization","Bearer "+key);}HttpResponse<String> response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new BusinessException(502,"EMBEDDING_CALL_FAILED","Embedding 调用失败，HTTP "+response.statusCode());JsonNode root=json.readTree(response.body());List<float[]> result=new ArrayList<>();JsonNode values=ollama?root.path("embeddings"):root.path("data");for(JsonNode item:values){JsonNode vector=ollama?item:item.path("embedding");float[] array=new float[vector.size()];for(int i=0;i<vector.size();i++)array[i]=(float)vector.get(i).asDouble();result.add(array);}if(result.size()!=input.size()||result.stream().anyMatch(x->x.length==0))throw new BusinessException(502,"EMBEDDING_INVALID_RESPONSE","Embedding 服务返回的向量数量或维度不正确");return result;}catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(502,"EMBEDDING_CALL_FAILED","Embedding 调用失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}}
 private Config config(UUID id){return jdbc.query("SELECT v.provider,v.endpoint,v.model_name,v.secret_version_id,v.timeout_ms FROM embedding_config c JOIN embedding_version v ON v.id=c.active_version_id WHERE c.id=? AND c.enabled=TRUE AND v.test_status='PASSED'",rs->{if(!rs.next())throw new BusinessException(422,"EMBEDDING_UNAVAILABLE","Embedding 模型未启用或未通过测试");return new Config(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5));},id.toString());}
 public static String vector(float[] values){StringBuilder b=new StringBuilder("[");for(int i=0;i<values.length;i++){if(i>0)b.append(',');b.append(values[i]);}return b.append(']').toString();}
 private record Config(String provider,String endpoint,String model,String secretId,int timeout){}
}
