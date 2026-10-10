package com.demo.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// com.demo.auth.provider: OAuthFlow and whichever provider jars are on the classpath, from libs/auth-provider.
@SpringBootApplication(scanBasePackages = {"com.demo.authservice", "com.demo.auth.provider"})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }

}
