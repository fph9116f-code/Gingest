package com.backend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;


@SpringBootApplication
@Slf4j
public class GingestApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(GingestApplication.class, args).getEnvironment();
        String port = env.getProperty("server.port", "8080");
        log.info("""
                ========================================================
                \tGingest 服务启动成功！
                \t本地接口文档地址: \thttp://127.0.0.1:{}/doc.html
                ========================================================""", port);
    }

}
