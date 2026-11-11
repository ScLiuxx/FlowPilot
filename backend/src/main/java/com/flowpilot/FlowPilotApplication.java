package com.flowpilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FlowPilot backend entry point.
 *
 * <p>The backend is a modular monolith: user, workflow, execution, messaging, agent, approval,
 * report and monitoring modules live in one deployable unit but talk to each other through
 * explicit service interfaces. The Python agent service stays a separate deployment and is
 * reached over RabbitMQ only.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("com.flowpilot.**.mapper")
public class FlowPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowPilotApplication.class, args);
    }
}
