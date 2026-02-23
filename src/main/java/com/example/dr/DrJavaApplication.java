package com.example.dr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Document Reader AI application.
 *
 * <p>Bootstraps the Spring Boot context, auto-configures all components,
 * and starts the embedded web server.</p>
 */
@SpringBootApplication
public class DrJavaApplication {

	/**
	 * Starts the Spring Boot application.
	 *
	 * @param args command-line arguments passed to the JVM
	 */
	public static void main(String[] args) {
		SpringApplication.run(DrJavaApplication.class, args);
	}

}
