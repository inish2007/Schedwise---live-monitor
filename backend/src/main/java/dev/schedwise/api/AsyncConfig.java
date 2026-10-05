package dev.schedwise.api;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class AsyncConfig implements WebMvcConfigurer {
 @Bean public ThreadPoolTaskExecutor streamExecutor(){
  ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(8);executor.setMaxPoolSize(8);executor.setQueueCapacity(0);executor.setThreadNamePrefix("sse-");return executor;
 }
 @Override public void configureAsyncSupport(AsyncSupportConfigurer config){config.setTaskExecutor(streamExecutor()).setDefaultTimeout(30000);}
}
