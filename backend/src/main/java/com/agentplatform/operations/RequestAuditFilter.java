package com.agentplatform.operations;

import com.agentplatform.identity.PlatformPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestAuditFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(RequestAuditFilter.class);
    private final JdbcTemplate jdbc;
    public RequestAuditFilter(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String requestId=request.getHeader("X-Request-ID");
        if(requestId==null||!requestId.matches("[A-Za-z0-9._-]{8,64}"))requestId=UUID.randomUUID().toString();
        response.setHeader("X-Request-ID",requestId);MDC.put("requestId",requestId);long start=System.nanoTime();
        try{chain.doFilter(request,response);}finally{
            try{auditMutation(request,response,requestId,(System.nanoTime()-start)/1_000_000);}catch(Exception e){log.error("操作审计写入失败 requestId={} path={}",requestId,request.getRequestURI(),e);}finally{MDC.remove("requestId");}
        }
    }
    private void auditMutation(HttpServletRequest request,HttpServletResponse response,String requestId,long duration){
        String method=request.getMethod(),path=request.getRequestURI();
        if(!path.startsWith("/api/v1/")||method.matches("GET|HEAD|OPTIONS"))return;
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!(auth.getPrincipal() instanceof PlatformPrincipal principal))return;
        String ip=request.getHeader("X-Forwarded-For");if(ip==null||ip.isBlank())ip=request.getRemoteAddr();else ip=ip.split(",")[0].trim();
        String details="{\"method\":\""+method+"\",\"path\":\""+path.replace("\"","")+"\",\"status\":"+response.getStatus()+",\"durationMs\":"+duration+"}";
        jdbc.update("INSERT INTO audit_log(id,actor_id,actor_username,action,target_type,result,request_id,ip_address,details_json,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),principal.id().toString(),principal.username(),"API_MUTATION","HTTP_ENDPOINT",response.getStatus()<400?"SUCCESS":"FAILED",requestId,ip,details,com.agentplatform.common.DatabaseTime.now());
    }
}
