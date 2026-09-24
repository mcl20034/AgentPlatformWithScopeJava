package com.agentplatform.knowledge;
import org.springframework.context.annotation.*;import org.springframework.scheduling.annotation.AsyncConfigurer;import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;import java.util.concurrent.Executor;
@Configuration public class AsyncConfiguration implements AsyncConfigurer{
 @Bean public Executor documentTaskExecutor(){ThreadPoolTaskExecutor e=new ThreadPoolTaskExecutor();e.setCorePoolSize(1);e.setMaxPoolSize(1);e.setQueueCapacity(100);e.setThreadNamePrefix("document-");e.initialize();return e;}
 @Bean public Executor chatTaskExecutor(){ThreadPoolTaskExecutor e=new ThreadPoolTaskExecutor();e.setCorePoolSize(2);e.setMaxPoolSize(4);e.setQueueCapacity(20);e.setThreadNamePrefix("chat-");e.initialize();return e;}
 @Override public Executor getAsyncExecutor(){return documentTaskExecutor();}
}
