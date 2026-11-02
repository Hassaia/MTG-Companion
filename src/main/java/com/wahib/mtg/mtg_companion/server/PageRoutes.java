package com.wahib.mtg.mtg_companion.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Adresses courtes des pages statiques ; « / » sert index.html automatiquement. */
@Configuration
public class PageRoutes implements WebMvcConfigurer {

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addViewController("/config").setViewName("forward:/config.html");
		registry.addViewController("/table").setViewName("forward:/table.html");
		registry.addViewController("/joueur").setViewName("forward:/joueur.html");
	}
}
