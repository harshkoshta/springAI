package com.local.rag;

import org.springframework.boot.SpringApplication;

public class TestRagApplication {

	public static void main(String[] args) {
		SpringApplication.from(RagApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
