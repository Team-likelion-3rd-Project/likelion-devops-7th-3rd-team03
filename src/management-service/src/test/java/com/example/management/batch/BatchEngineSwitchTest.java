package com.example.management.batch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.athena.AthenaClient;

import static org.assertj.core.api.Assertions.assertThat;

class BatchEngineSwitchTest {

    // AthenaClient.builder().build()가 리전을 요구한다. 자격증명은 첫 호출 때 읽으므로 없어도 된다.
    @BeforeAll
    static void setRegion() {
        System.setProperty("aws.region", "ap-southeast-1");
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("batch"))
            .withUserConfiguration(BatchAwsConfig.class, AthenaQueryExecutor.class, AthenaQueries.class)
            .withPropertyValues("app.athena.workgroup=test-workgroup");

    @Test
    void engine_미지정시_기존처럼_Athena_빈이_뜬다() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AthenaClient.class);
            assertThat(ctx).hasSingleBean(ClickQueryExecutor.class);
            assertThat(ctx).hasSingleBean(ClickStatsQueries.class);
        });
    }

    @Test
    void engine이_duckdb면_Athena_빈이_뜨지_않는다() {
        runner.withPropertyValues("app.batch.engine=duckdb")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(AthenaClient.class);
                    assertThat(ctx).doesNotHaveBean(ClickQueryExecutor.class);
                    assertThat(ctx).doesNotHaveBean(ClickStatsQueries.class);
                });
    }
}