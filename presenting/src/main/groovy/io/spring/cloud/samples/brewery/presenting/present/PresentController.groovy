package io.spring.cloud.samples.brewery.presenting.present

import groovy.transform.TypeChecked
import groovy.util.logging.Slf4j
import io.opentracing.ActiveSpan
import io.opentracing.Span
import io.opentracing.Tracer
import io.spring.cloud.samples.brewery.common.TestConfigurationHolder
import io.spring.cloud.samples.brewery.presenting.config.Collaborators
import io.spring.cloud.samples.brewery.presenting.config.Versions
import io.spring.cloud.samples.brewery.presenting.feed.FeedRepository
import io.spring.cloud.samples.brewery.presenting.feed.ProcessState
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.cloud.kubernetes.discovery.KubernetesDiscoveryClient
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.util.JdkIdGenerator
import org.springframework.util.StringUtils
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate

import static io.spring.cloud.samples.brewery.common.TestConfigurationHolder.TestCommunicationType.FEIGN
import static io.spring.cloud.samples.brewery.common.TestRequestEntityBuilder.requestEntity
import static org.springframework.web.bind.annotation.RequestMethod.GET
import static org.springframework.web.bind.annotation.RequestMethod.POST

@Slf4j
@RestController
@RequestMapping('/present')
@TypeChecked
class PresentController {

    public static final String PROCESS_ID_HEADER_NAME = 'PROCESS-ID'

    private final FeedRepository feedRepository
    private final Tracer tracer
    private final BrewingServiceClient aggregationServiceClient
    private final RestTemplate restTemplate

    private final KubernetesDiscoveryClient discoveryClient;

    @Autowired
    public PresentController(FeedRepository feedRepository, Tracer tracer,
                             BrewingServiceClient aggregationServiceClient, @LoadBalanced RestTemplate restTemplate,
                             KubernetesDiscoveryClient discoveryClient) {
        this.feedRepository = feedRepository
        this.tracer = tracer
        this.aggregationServiceClient = aggregationServiceClient
        this.restTemplate = restTemplate
        this.discoveryClient = discoveryClient;
    }

    @RequestMapping(
        value = "/order",
        method = POST)
    String order(HttpEntity<String> body) {
        String processIdFromHeaders = body.headers.getFirst(PROCESS_ID_HEADER_NAME);
        String processId = StringUtils.hasText(body.headers.getFirst(PROCESS_ID_HEADER_NAME)) ?
            processIdFromHeaders :
            new JdkIdGenerator().generateId().toString()
        log.info("Making new order with [$body.body] and processid [$processId].")
        ActiveSpan span = this.tracer
            .buildSpan("inside_presenting")
            .startActive()
        try {
            switch (TestConfigurationHolder.TEST_CONFIG.get().getTestCommunicationType()) {
                case FEIGN:
                    return useFeignToCallAggregation(body, processId)
                default:
                    return useRestTemplateToCallAggregation(body, processId)
            }
        } finally {
            span.close()
        }
    }

    private String useRestTemplateToCallAggregation(HttpEntity<String> body, String processId) {

        return restTemplate.exchange(requestEntity()
            .contentTypeVersion(Versions.BREWING_CONTENT_TYPE_V1)
            .serviceName(Collaborators.BREWING)
            .url("ingredients")
            .httpMethod(HttpMethod.POST)
            .processId(processId)
            .body(body.body)
            .build(), String.class).body;
    }

    private String useFeignToCallAggregation(HttpEntity<String> body, String processId) {
        return aggregationServiceClient.getIngredients(body.body,
            processId,
            TestConfigurationHolder.TEST_CONFIG.get().getTestCommunicationType().name())
    }

    @RequestMapping(value = "/maturing", method = GET)
    String maturing() {
        return feedRepository.countFor(ProcessState.MATURING)
    }

    @RequestMapping(value = "/bottling", method = GET)
    String bottling() {
        return feedRepository.countFor(ProcessState.BOTTLING)
    }

    @RequestMapping(value = "/bottles", method = GET)
    String bottles() {
        return feedRepository.getBottles()
    }
}
