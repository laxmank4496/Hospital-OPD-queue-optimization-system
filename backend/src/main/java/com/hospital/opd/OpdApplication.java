package com.hospital.opd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OpdApplication {
    public static void main(String[] args) { SpringApplication.run(OpdApplication.class, args); }
}
