/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.web.embedded.tomcat;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.util.Set;

import org.apache.catalina.LifecycleState;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.testsupport.system.CapturedOutput;
import org.springframework.boot.testsupport.system.OutputCaptureExtension;
import org.springframework.boot.testsupport.web.servlet.DirtiesUrlFactories;
import org.springframework.boot.web.embedded.netty.MockPkcs11SecurityProvider;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.SslStoreProvider;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link SslConnectorCustomizer}
 *
 * @author Brian Clozel
 * @author Andy Wilkinson
 * @author Scott Frederick
 * @author Cyril Dangerville
 */
@ExtendWith(OutputCaptureExtension.class)
@DirtiesUrlFactories
class SslConnectorCustomizerTests {

	private static final Provider PKCS11_PROVIDER = new MockPkcs11SecurityProvider();

	private Tomcat tomcat;

	private Connector connector;

	@BeforeAll
	static void beforeAllTests() {
		/*
		 * Add the mock Java security provider for PKCS#11-related unit tests.
		 *
		 */
		Security.addProvider(PKCS11_PROVIDER);
	}

	@AfterAll
	static void afterAllTests() {
		// Remove the provider previously added in setup()
		Security.removeProvider(PKCS11_PROVIDER.getName());
	}

	@BeforeEach
	void setup() {
		this.tomcat = new Tomcat();
		this.connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
		this.connector.setPort(0);
		this.tomcat.setConnector(this.connector);
	}

	@AfterEach
	void stop() throws Exception {
		System.clearProperty("javax.net.ssl.trustStorePassword");
		this.tomcat.stop();
	}

	@Test
	void sslCiphersConfiguration() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyStore("test.jks");
		ssl.setKeyStorePassword("secret");
		ssl.setCiphers(new String[] { "ALPHA", "BRAVO", "CHARLIE" });
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(ssl, null);
		Connector connector = this.tomcat.getConnector();
		customizer.customize(connector);
		this.tomcat.start();
		SSLHostConfig[] sslHostConfigs = connector.getProtocolHandler().findSslHostConfigs();
		assertThat(sslHostConfigs[0].getCiphers()).isEqualTo("ALPHA:BRAVO:CHARLIE");
	}

	@Test
	void sslEnabledMultipleProtocolsConfiguration() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setKeyStore("src/test/resources/test.jks");
		ssl.setEnabledProtocols(new String[] { "TLSv1.1", "TLSv1.2" });
		ssl.setCiphers(new String[] { "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "BRAVO" });
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(ssl, null);
		Connector connector = this.tomcat.getConnector();
		customizer.customize(connector);
		this.tomcat.start();
		SSLHostConfig sslHostConfig = connector.getProtocolHandler().findSslHostConfigs()[0];
		assertThat(sslHostConfig.getSslProtocol()).isEqualTo("TLS");
		assertThat(sslHostConfig.getEnabledProtocols()).containsExactlyInAnyOrder("TLSv1.1", "TLSv1.2");
	}

	@Test
	void sslEnabledProtocolsConfiguration() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setKeyStore("src/test/resources/test.jks");
		ssl.setEnabledProtocols(new String[] { "TLSv1.2" });
		ssl.setCiphers(new String[] { "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "BRAVO" });
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(ssl, null);
		Connector connector = this.tomcat.getConnector();
		customizer.customize(connector);
		this.tomcat.start();
		SSLHostConfig sslHostConfig = connector.getProtocolHandler().findSslHostConfigs()[0];
		assertThat(sslHostConfig.getSslProtocol()).isEqualTo("TLS");
		assertThat(sslHostConfig.getEnabledProtocols()).containsExactly("TLSv1.2");
	}

	@Test
	void customizeWhenSslStoreProviderProvidesOnlyKeyStoreShouldUseDefaultTruststore() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setTrustStore("src/test/resources/test.jks");
		SslStoreProvider sslStoreProvider = mock(SslStoreProvider.class);
		KeyStore keyStore = loadStore();
		given(sslStoreProvider.getKeyStore()).willReturn(keyStore);
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(ssl, sslStoreProvider);
		Connector connector = this.tomcat.getConnector();
		customizer.customize(connector);
		this.tomcat.start();
		SSLHostConfig sslHostConfig = connector.getProtocolHandler().findSslHostConfigs()[0];
		SSLHostConfig sslHostConfigWithDefaults = new SSLHostConfig();
		assertThat(sslHostConfig.getTruststoreFile()).isEqualTo(sslHostConfigWithDefaults.getTruststoreFile());
		Set<SSLHostConfigCertificate> certificates = sslHostConfig.getCertificates();
		assertThat(certificates).hasSize(1);
		assertThat(certificates.iterator().next().getCertificateKeystore()).isEqualTo(keyStore);
	}

	@Test
	void customizeWhenSslStoreProviderProvidesOnlyTrustStoreShouldUseDefaultKeystore() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setKeyStore("src/test/resources/test.jks");
		SslStoreProvider sslStoreProvider = mock(SslStoreProvider.class);
		KeyStore trustStore = loadStore();
		given(sslStoreProvider.getTrustStore()).willReturn(trustStore);
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(ssl, sslStoreProvider);
		Connector connector = this.tomcat.getConnector();
		customizer.customize(connector);
		this.tomcat.start();
		SSLHostConfig sslHostConfig = connector.getProtocolHandler().findSslHostConfigs()[0];
		assertThat(sslHostConfig.getTruststore()).isEqualTo(trustStore);
	}

	@Test
	void customizeWhenSslStoreProviderPresentShouldIgnorePasswordFromSsl(CapturedOutput output) throws Exception {
		System.setProperty("javax.net.ssl.trustStorePassword", "trustStoreSecret");
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setKeyStorePassword("secret");
		SslStoreProvider sslStoreProvider = mock(SslStoreProvider.class);
		given(sslStoreProvider.getTrustStore()).willReturn(loadStore());
		given(sslStoreProvider.getKeyStore()).willReturn(loadStore());
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(ssl, sslStoreProvider);
		Connector connector = this.tomcat.getConnector();
		customizer.customize(connector);
		this.tomcat.start();
		assertThat(connector.getState()).isEqualTo(LifecycleState.STARTED);
		assertThat(output).doesNotContain("Password verification failed");
	}

	/**
	 * Null/undefined keystore is invalid unless keystore type is PKCS11.
	 */
	@Test
	void customizeWhenSslIsEnabledWithNoKeyStoreAndNotPkcs11ThrowsWebServerException() {
		assertThatExceptionOfType(WebServerException.class)
				.isThrownBy(() -> new SslConnectorCustomizer(new Ssl(), null).customize(this.tomcat.getConnector()))
				.withMessageContaining("Could not load key store 'null'");
	}

	/**
	 * No keystore path should be defined if keystore type is PKCS#11.
	 */
	@Test
	void customizeWhenSslIsEnabledWithPkcs11AndKeyStoreThrowsIllegalArgumentException() {
		Ssl ssl = new Ssl();
		ssl.setKeyStoreType("PKCS11");
		ssl.setKeyStoreProvider(PKCS11_PROVIDER.getName());
		ssl.setKeyStore("src/test/resources/test.jks");
		ssl.setKeyPassword("password");
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(ssl, null);
		assertThatIllegalArgumentException().isThrownBy(() -> customizer.customize(this.tomcat.getConnector()))
				.withMessageContaining("Input keystore location is not valid for keystore type 'PKCS11'");
	}

	@Test
	void customizeWhenSslIsEnabledWithPkcs11AndKeyStoreProvider() {
		Ssl ssl = new Ssl();
		ssl.setKeyStoreType("PKCS11");
		ssl.setKeyStoreProvider(PKCS11_PROVIDER.getName());
		ssl.setKeyStorePassword("1234");
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(ssl, null);
		// Loading the KeyManagerFactory should be successful
		assertThatNoException().isThrownBy(() -> customizer.customize(this.tomcat.getConnector()));
	}

	private KeyStore loadStore() throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		Resource resource = new ClassPathResource("test.jks");
		try (InputStream stream = resource.getInputStream()) {
			keyStore.load(stream, "secret".toCharArray());
			return keyStore;
		}
	}

}
