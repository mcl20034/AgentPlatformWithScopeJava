package com.agentplatform.chat;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ChatTaskRecovery implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    ChatTaskRecovery(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public void run(ApplicationArguments args){
        var now=com.agentplatform.common.DatabaseTime.now();
        jdbc.update("UPDATE analysis_task SET status='FAILED',stage='FAILED',error_code='SERVICE_RESTARTED',error_message='服务重启，原任务已安全终止，请重新提问',completed_at=?,updated_at=? WHERE status IN ('QUEUED','RUNNING')",now,now);
        jdbc.update("UPDATE chat_message SET status='FAILED',content='服务重启，原任务已安全终止，请重新提问' WHERE status='RUNNING'");
    }
}
