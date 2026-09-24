package com.agentplatform.knowledge;

import com.agentplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class KnowledgeRetriever {
 private final JdbcTemplate jdbc;private final EmbeddingClient embeddings;
 public KnowledgeRetriever(JdbcTemplate jdbc,EmbeddingClient embeddings){this.jdbc=jdbc;this.embeddings=embeddings;}
 public List<Map<String,Object>> retrieve(List<String> kbIds,String question,int limit){if(kbIds==null||kbIds.isEmpty())return List.of();List<Map<String,Object>> configs=jdbc.queryForList("SELECT id,embedding_id FROM knowledge_base WHERE enabled=TRUE AND active_generation_id IS NOT NULL AND id IN ("+placeholders(kbIds.size())+")",kbIds.toArray());if(configs.size()!=kbIds.size())throw new BusinessException(422,"KNOWLEDGE_UNAVAILABLE","所选知识库未启用或没有已发布索引");Map<String,List<String>> byEmbedding=new LinkedHashMap<>();for(Map<String,Object> row:configs)byEmbedding.computeIfAbsent(String.valueOf(row.get("embedding_id")),x->new ArrayList<>()).add(String.valueOf(row.get("id")));List<Map<String,Object>> result=new ArrayList<>();for(var group:byEmbedding.entrySet()){float[] vector=embeddings.embed(UUID.fromString(group.getKey()),List.of(question)).getFirst();String encoded=EmbeddingClient.vector(vector);List<Object> args=new ArrayList<>();args.add(encoded);args.addAll(group.getValue());args.add(encoded);args.add(Math.min(limit,12));result.addAll(jdbc.queryForList("SELECT c.id chunk_id,k.id kb_id,k.name knowledge_name,d.normalized_name document_name,c.page_number,c.chunk_index,c.content,(1-(c.embedding_vector <=> CAST(? AS vector))) score FROM document_chunk c JOIN kb_generation g ON g.id=c.generation_id JOIN knowledge_base k ON k.active_generation_id=g.id JOIN document_version v ON v.id=c.document_version_id JOIN document d ON d.id=v.document_id WHERE k.id IN ("+placeholders(group.getValue().size())+") ORDER BY c.embedding_vector <=> CAST(? AS vector) LIMIT ?",args.toArray()));}result.sort(Comparator.comparingDouble(x->-((Number)x.get("score")).doubleValue()));return result.subList(0,Math.min(limit,result.size()));}
 private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
}
