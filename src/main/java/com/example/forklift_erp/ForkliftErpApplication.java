package com.example.forklift_erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ForkliftErpApplication {

	public static void main(String[] args) {
		SpringApplication.run(ForkliftErpApplication.class, args);
	}

}
