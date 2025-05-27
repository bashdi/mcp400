package com.github.bashdi.mcp400;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class Mcp400 {

	public static void main(String[] args) {
		SpringApplication.run(Mcp400.class, args);
	}

	@Bean
	public List<ToolCallback> mcp400Tools(MCP400Service mcp400Service) {
		return List.of(ToolCallbacks.from(mcp400Service));
	}

}
