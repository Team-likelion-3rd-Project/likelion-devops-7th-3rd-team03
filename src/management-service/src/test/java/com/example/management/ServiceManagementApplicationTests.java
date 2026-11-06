package com.example.management;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:management;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.flyway.enabled=false",
		"kakao.client-id=test-client-id",
		"kakao.redirect-uri=https://example.com/auth/callback",
		"jwt.secret=01234567890123456789012345678901",
		"short-url.base-url=https://s.short.ly"
})
class ServiceManagementApplicationTests {

	@Test
	void contextLoads() {
	}

}
