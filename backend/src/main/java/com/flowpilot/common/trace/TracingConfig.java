package com.flowpilot.common.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The OpenTelemetry setup of the backend.
 *
 * <p>The platform uses OpenTelemetry as the single tracing standard, so the Java service, the
 * message envelope and the Python agent service share one trace. The trace id of the envelope is
 * already a 32 character hex string, which is exactly the format OpenTelemetry expects, so no
 * translation is needed when a message crosses the process boundary.
 *
 * <p>The exporter is optional. Without an endpoint the spans are still created and can be read
 * from the log, but nothing is sent to a collector.
 */
@Configuration
public class TracingConfig {

    /** The name of the service as it appears in the traces. */
    public static final String SERVICE_NAME = "flowpilot-backend";

    @Value("${flowpilot.tracing.service-name:flowpilot-backend}")
    private String serviceName;

    @Value("${flowpilot.tracing.exporter-otlp-endpoint:}")
    private String otlpEndpoint;

    @Value("${flowpilot.tracing.exporter-otlp-timeout-seconds:10}")
    private long otlpTimeoutSeconds;

    @Value("${flowpilot.tracing.sampling-probability:1.0}")
    private double samplingProbability;

    @Bean(destroyMethod = "close")
    public SdkTracerProvider sdkTracerProvider() {
        io.opentelemetry.sdk.trace.SdkTracerProviderBuilder providerBuilder =
                SdkTracerProvider.builder()
                        .setResource(Resource.getDefault().toBuilder()
                                .put("service.name", serviceName)
                                .build())
                        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(samplingProbability)));

        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(otlpEndpoint)
                    .setTimeout(Duration.ofSeconds(otlpTimeoutSeconds))
                    .build();
            providerBuilder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }
        return providerBuilder.build();
    }

    /**
     * The OpenTelemetry instance of the service.
     *
     * <p>The instance is not registered globally. The global registration can only happen once
     * per process, and a process that runs several application contexts would fail on the second
     * one. Every component gets the tracer through the injection instead.
     */
    @Bean
    public OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider) {
        TextMapPropagator propagator = W3CTraceContextPropagator.getInstance();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(propagator))
                .build();
    }

    @Bean
    public Tracer flowPilotTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(SERVICE_NAME);
    }

    /**
     * The attribute key of the workflow execution id.
     */
    public static final AttributeKey<String> WORKFLOW_EXECUTION_ID =
            AttributeKey.stringKey("flowpilot.workflow_execution_id");

    /**
     * The attribute key of the node execution id.
     */
    public static final AttributeKey<String> NODE_EXECUTION_ID =
            AttributeKey.stringKey("flowpilot.node_execution_id");

    /**
     * The attribute key of the node key.
     */
    public static final AttributeKey<String> NODE_KEY =
            AttributeKey.stringKey("flowpilot.node_key");

    /**
     * The attribute key of the node type.
     */
    public static final AttributeKey<String> NODE_TYPE =
            AttributeKey.stringKey("flowpilot.node_type");

    /**
     * The attribute key of the invocation id.
     */
    public static final AttributeKey<String> INVOCATION_ID =
            AttributeKey.stringKey("flowpilot.invocation_id");

    /**
     * The attribute key of the error code.
     */
    public static final AttributeKey<String> ERROR_CODE =
            AttributeKey.stringKey("flowpilot.error_code");
}
