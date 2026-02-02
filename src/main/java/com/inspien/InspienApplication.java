package com.inspien;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InspienApplication {

	public static void main(String[] args) {
		SpringApplication.run(InspienApplication.class, args);
	}

}
