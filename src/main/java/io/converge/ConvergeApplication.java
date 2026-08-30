package io.converge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;

@Modulith
@SpringBootApplication
public class ConvergeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConvergeApplication.class, args);
    }
}

