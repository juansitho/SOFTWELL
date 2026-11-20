package com.softwell.softwell;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.util.Locale;

@SpringBootApplication
public class SoftwellApplication {

	@Bean
	public LocaleResolver localeResolver() {
		return new FixedLocaleResolver(Locale.forLanguageTag("es-CO"));
	}

	public static void main(String[] args) {
		SpringApplication.run(SoftwellApplication.class, args);
	}

}
