/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.profiles.ProfileFileSystemSetting;
import software.amazon.awssdk.services.sso.SsoClient;
import software.amazon.awssdk.services.sso.SsoClientBuilder;
import software.amazon.awssdk.services.sso.model.GetRoleCredentialsRequest;
import software.amazon.awssdk.services.sso.model.GetRoleCredentialsResponse;
import software.amazon.awssdk.services.sso.model.RoleCredentials;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.utils.internal.SystemSettingUtilsTestBackdoor;

/**
 * Verifies signing headers, payloads, credential refresh, and Java SDK credential providers.
 *
 * <p>The cases cover commercial and China endpoints for all three signals.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AwsAuthHeaderSupplierTest {
  private static final String AUTHORIZATION = "Authorization";
  private static final String ACCESS_KEY = "TESTACCESSKEY";
  private static final String SECRET_KEY = "test-secret";
  private static final String SESSION_TOKEN = "test-session-token";
  private static final Instant EXPIRATION = Instant.parse("2099-01-01T00:00:00Z");
  private static final String ENDPOINT = "https://test-service.us-west-2.amazonaws.com/otlp";
  private static final byte[] PAYLOAD = {0, 1, 127, (byte) 128, (byte) 255};

  private static final AwsCredentials BASIC_CREDENTIALS =
      AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);
  private static final AwsCredentials SESSION_CREDENTIALS =
      AwsSessionCredentials.create(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN);

  @TempDir Path directory;
  private TestExporter exporter;
  private AwsCredentialProviderHelper environment;

  @BeforeEach
  void setup() throws Exception {
    environment = new AwsCredentialProviderHelper(directory);
    exporter = new TestExporter(ENDPOINT, "us-west-2", "test-service", CompressionMethod.NONE);
  }

  @AfterEach
  void cleanup() {
    try {
      if (exporter != null) {
        exporter.data.set(null);
      }
    } finally {
      if (environment != null) {
        environment.close();
      }
    }
  }

  // Authentication headers.

  @ParameterizedTest
  @MethodSource("awsOtlpEndpoints")
  void testSignsEndpointWithExpectedRegionServiceAndUri(
      String endpoint, String expectedRegion, String expectedService) throws Exception {
    environment.environment("AWS_ACCESS_KEY_ID", ACCESS_KEY);
    environment.environment("AWS_SECRET_ACCESS_KEY", SECRET_KEY);
    environment.environment("AWS_SESSION_TOKEN", SESSION_TOKEN);
    exporter = new TestExporter(endpoint, expectedRegion, expectedService, CompressionMethod.NONE);
    assertHeadersForPayload(exporter, SESSION_CREDENTIALS);
    assertTrue(environment.requests.isEmpty());
  }

  @Test
  void testShouldSignCompressedPayload() throws Exception {
    environment.environment("AWS_ACCESS_KEY_ID", ACCESS_KEY);
    environment.environment("AWS_SECRET_ACCESS_KEY", SECRET_KEY);
    environment.environment("AWS_SESSION_TOKEN", SESSION_TOKEN);
    exporter =
        new TestExporter(
            "https://logs.us-west-2.amazonaws.com/v1/logs",
            "us-west-2",
            "logs",
            CompressionMethod.GZIP);
    assertHeadersForPayload(exporter, SESSION_CREDENTIALS);
    assertTrue(environment.requests.isEmpty());
  }

  @Test
  void testShouldReplaceStaleSigningHeadersWithFreshCredentials() throws Exception {
    environment.environment("AWS_ACCESS_KEY_ID", "FIRSTKEY");
    environment.environment("AWS_SECRET_ACCESS_KEY", "first-secret");
    environment.environment("AWS_SESSION_TOKEN", "first-token");
    Map<String, String> first =
        assertHeadersForPayload(
            exporter, AwsSessionCredentials.create("FIRSTKEY", "first-secret", "first-token"));

    environment.environment("AWS_ACCESS_KEY_ID", "SECONDKEY");
    environment.environment("AWS_SECRET_ACCESS_KEY", "second-secret");
    environment.environment("AWS_SESSION_TOKEN", null);
    Map<String, String> second =
        assertHeadersForPayload(exporter, AwsBasicCredentials.create("SECONDKEY", "second-secret"));

    assertTrue(first.get(AUTHORIZATION).contains("Credential=FIRSTKEY/"));
    assertEquals("first-token", first.get("x-amz-security-token"));
    assertTrue(second.get(AUTHORIZATION).contains("Credential=SECONDKEY/"));
    assertFalse(second.containsKey("x-amz-security-token"));
    assertNotEquals(first.get(AUTHORIZATION), second.get(AUTHORIZATION));
    assertEquals("first-token", first.get("x-amz-security-token"));
  }

  // Credential providers.

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testShouldResolveEnvironmentCredentials(boolean useSessionToken) throws Exception {
    environment.environment("AWS_ACCESS_KEY_ID", ACCESS_KEY);
    environment.environment("AWS_SECRET_ACCESS_KEY", SECRET_KEY);
    environment.environment("AWS_SESSION_TOKEN", useSessionToken ? SESSION_TOKEN : null);
    assertHeadersForPayload(exporter, useSessionToken ? SESSION_CREDENTIALS : BASIC_CREDENTIALS);
    assertTrue(environment.requests.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testShouldResolveJavaSystemPropertyCredentials(boolean useSessionToken) throws Exception {
    System.setProperty("aws.accessKeyId", ACCESS_KEY);
    System.setProperty("aws.secretAccessKey", SECRET_KEY);
    if (useSessionToken) {
      System.setProperty("aws.sessionToken", SESSION_TOKEN);
    }
    assertHeadersForPayload(exporter, useSessionToken ? SESSION_CREDENTIALS : BASIC_CREDENTIALS);
    assertTrue(environment.requests.isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"default,false,false", "named-profile,true,false", "sdk-profile,true,true"})
  void testShouldResolveSharedCredentialsProfiles(
      String profile, boolean useSessionToken, boolean selectThroughSdk) throws Exception {
    environment.writeCredentials(
        "["
            + profile
            + "]\n"
            + "aws_access_key_id = "
            + ACCESS_KEY
            + "\n"
            + "aws_secret_access_key = "
            + SECRET_KEY
            + "\n"
            + (useSessionToken ? "aws_session_token = " + SESSION_TOKEN + "\n" : ""));
    if (selectThroughSdk) {
      System.setProperty("aws.profile", profile);
    } else if (!profile.equals("default")) {
      environment.environment("AWS_PROFILE", profile);
    }
    assertHeadersForPayload(exporter, useSessionToken ? SESSION_CREDENTIALS : BASIC_CREDENTIALS);
    assertTrue(environment.requests.isEmpty());
  }

  @Test
  void testShouldResolveEksWebIdentityCredentials() throws Exception {
    try (MockedStatic<StsClient> stsFactory = mockStatic(StsClient.class)) {
      Path tokenFile = directory.resolve("web-identity-token");
      Files.write(tokenFile, "test-web-identity-token".getBytes(StandardCharsets.UTF_8));
      environment.environment("AWS_WEB_IDENTITY_TOKEN_FILE", tokenFile.toString());
      environment.environment(
          "AWS_ROLE_ARN", "arn:aws:iam::111122223333:role/test-web-identity-role");
      environment.environment("AWS_ROLE_SESSION_NAME", "test-web-identity-session");
      StsClient sts = mock(StsClient.class);
      StsClientBuilder builder = mock(StsClientBuilder.class, RETURNS_SELF);
      stsFactory.when(StsClient::builder).thenReturn(builder);
      when(builder.build()).thenReturn(sts);
      when(sts.assumeRoleWithWebIdentity(any(AssumeRoleWithWebIdentityRequest.class)))
          .thenReturn(
              AssumeRoleWithWebIdentityResponse.builder().credentials(roleCredentials()).build());

      assertHeadersForPayload(exporter, SESSION_CREDENTIALS);

      ArgumentCaptor<AssumeRoleWithWebIdentityRequest> request =
          ArgumentCaptor.forClass(AssumeRoleWithWebIdentityRequest.class);
      verify(sts).assumeRoleWithWebIdentity(request.capture());
      assertEquals("test-web-identity-token", request.getValue().webIdentityToken());
      assertEquals(
          "arn:aws:iam::111122223333:role/test-web-identity-role", request.getValue().roleArn());
      assertEquals("test-web-identity-session", request.getValue().roleSessionName());
      assertTrue(environment.requests.isEmpty());
    }
  }

  @Test
  void testShouldResolveAssumeRoleCredentialsFromASourceProfile() throws Exception {
    try (MockedStatic<StsClient> stsFactory = mockStatic(StsClient.class)) {
      environment.writeCredentials(
          "[source]\naws_access_key_id = SOURCEACCESSKEY\naws_secret_access_key = source-secret\n");
      environment.writeConfig(
          "[profile assume-role]\n"
              + "role_arn = arn:aws:iam::111122223333:role/test-assumed-role\n"
              + "source_profile = source\nrole_session_name = test-role-session\n");
      environment.environment("AWS_PROFILE", "assume-role");
      StsClient sts = mock(StsClient.class);
      StsClientBuilder builder = mock(StsClientBuilder.class, RETURNS_SELF);
      stsFactory.when(StsClient::builder).thenReturn(builder);
      when(builder.build()).thenReturn(sts);
      when(sts.assumeRole(any(AssumeRoleRequest.class)))
          .thenReturn(AssumeRoleResponse.builder().credentials(roleCredentials()).build());

      assertHeadersForPayload(exporter, SESSION_CREDENTIALS);

      ArgumentCaptor<AssumeRoleRequest> request = ArgumentCaptor.forClass(AssumeRoleRequest.class);
      verify(sts).assumeRole(request.capture());
      assertEquals(
          "arn:aws:iam::111122223333:role/test-assumed-role", request.getValue().roleArn());
      assertEquals("test-role-session", request.getValue().roleSessionName());
      assertTrue(environment.requests.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ecs", "eks", "lambda-snapstart"})
  void testShouldResolveContainerCredentials(String hostEnvironment) throws Exception {
    String path;
    String authorization = null;
    if (hostEnvironment.equals("ecs")) {
      path = "/test-task";
      environment.environment("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", path);
    } else if (hostEnvironment.equals("eks")) {
      path = "/test-pod";
      authorization = "test-pod-authorization";
      Path tokenFile = directory.resolve("pod-authorization-token");
      Files.write(tokenFile, authorization.getBytes(StandardCharsets.UTF_8));
      environment.environment("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", tokenFile.toString());
      environment.environment("AWS_CONTAINER_CREDENTIALS_FULL_URI", environment.origin() + path);
    } else {
      path = "/test-snapstart";
      authorization = "test-snapstart-authorization";
      environment.environment("AWS_CONTAINER_AUTHORIZATION_TOKEN", authorization);
      environment.environment("AWS_CONTAINER_CREDENTIALS_FULL_URI", environment.origin() + path);
    }

    assertHeadersForPayload(exporter, SESSION_CREDENTIALS);

    assertEquals(1, environment.requests.size());
    CredentialRequest request = environment.requests.get(0);
    assertEquals("GET", request.method);
    assertEquals(path, request.path);
    assertEquals(authorization, request.headers.get(AUTHORIZATION));
  }

  @Test
  void testShouldResolveCachedEc2InstanceProfileCredentialsUsingImdsv2() throws Exception {
    environment.environment("AWS_EC2_METADATA_DISABLED", "false");
    environment.environment("AWS_EC2_METADATA_V1_DISABLED", "true");
    assertHeadersForPayload(exporter, SESSION_CREDENTIALS);
    assertHeadersForPayload(exporter, SESSION_CREDENTIALS);

    assertTrue(
        environment.requests.stream()
            .anyMatch(
                request ->
                    request.method.equals("PUT") && request.path.equals("/latest/api/token")));
    List<CredentialRequest> metadataRequests = new ArrayList<>();
    for (CredentialRequest request : environment.requests) {
      if (request.method.equals("GET")) {
        metadataRequests.add(request);
        assertEquals("test-imds-token", request.headers.get("x-aws-ec2-metadata-token"));
      }
    }
    assertTrue(
        metadataRequests.stream()
            .anyMatch(
                request ->
                    request.path.equals("/latest/meta-data/iam/security-credentials/test-role")));
    assertEquals(
        1,
        metadataRequests.stream()
            .filter(
                request ->
                    request.path.equals("/latest/meta-data/iam/security-credentials/test-role"))
            .count());
  }

  @Test
  void testShouldResolveCredentialsFromAnExternalProcess() throws Exception {
    Path responseFile = directory.resolve("process-credentials.json");
    Files.write(
        responseFile,
        ("{\"Version\":1,\"AccessKeyId\":\""
                + ACCESS_KEY
                + "\",\"SecretAccessKey\":\""
                + SECRET_KEY
                + "\",\"SessionToken\":\""
                + SESSION_TOKEN
                + "\",\"Expiration\":\"2099-01-01T00:00:00Z\"}")
            .getBytes(StandardCharsets.UTF_8));
    String command =
        System.getProperty("os.name").startsWith("Windows")
            ? "cmd.exe /c type \"" + responseFile + "\""
            : "cat \"" + responseFile + "\"";
    environment.writeConfig("[profile process]\ncredential_process = " + command + "\n");
    environment.environment("AWS_PROFILE", "process");
    assertHeadersForPayload(exporter, SESSION_CREDENTIALS);
    assertTrue(environment.requests.isEmpty());
  }

  @Test
  void testShouldResolveConfiguredIamIdentityCenterCredentialsFromACachedSsoToken()
      throws Exception {
    try (MockedStatic<SsoClient> ssoFactory = mockStatic(SsoClient.class)) {
      String startUrl = "https://adot-test.awsapps.com/start";
      environment.writeConfig(
          "[profile sso]\nsso_account_id = 111122223333\nsso_role_name = test-role\n"
              + "sso_region = us-west-2\nsso_start_url = "
              + startUrl
              + "\n");
      environment.environment("AWS_PROFILE", "sso");
      Path cacheDirectory = Files.createDirectories(directory.resolve(".aws/sso/cache"));
      StringBuilder cacheName = new StringBuilder();
      for (byte value :
          MessageDigest.getInstance("SHA-1").digest(startUrl.getBytes(StandardCharsets.UTF_8))) {
        cacheName.append(String.format("%02x", value & 0xff));
      }
      Files.write(
          cacheDirectory.resolve(cacheName + ".json"),
          ("{\"startUrl\":\""
                  + startUrl
                  + "\",\"region\":\"us-west-2\","
                  + "\"accessToken\":\"test-sso-access-token\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}")
              .getBytes(StandardCharsets.UTF_8));
      SsoClient sso = mock(SsoClient.class);
      SsoClientBuilder builder = mock(SsoClientBuilder.class, RETURNS_SELF);
      ssoFactory.when(SsoClient::builder).thenReturn(builder);
      when(builder.build()).thenReturn(sso);
      when(sso.getRoleCredentials(any(GetRoleCredentialsRequest.class)))
          .thenReturn(
              GetRoleCredentialsResponse.builder()
                  .roleCredentials(
                      RoleCredentials.builder()
                          .accessKeyId(ACCESS_KEY)
                          .secretAccessKey(SECRET_KEY)
                          .sessionToken(SESSION_TOKEN)
                          .expiration(EXPIRATION.toEpochMilli())
                          .build())
                  .build());

      assertHeadersForPayload(exporter, SESSION_CREDENTIALS);

      ArgumentCaptor<GetRoleCredentialsRequest> request =
          ArgumentCaptor.forClass(GetRoleCredentialsRequest.class);
      verify(sso).getRoleCredentials(request.capture());
      assertEquals("111122223333", request.getValue().accountId());
      assertEquals("test-role", request.getValue().roleName());
      assertEquals("test-sso-access-token", request.getValue().accessToken());
      assertTrue(environment.requests.isEmpty());
    }
  }

  @Test
  void testShouldReturnEmptyHeadersWhenNoCredentialProviderIsAvailable() throws Exception {
    assertHeadersForPayload(exporter, null);
    assertTrue(environment.requests.isEmpty());
  }

  private static Map<String, String> assertHeadersForPayload(
      TestExporter exporter, AwsCredentials credentials) throws Exception {
    if (credentials != null) {
      AwsCredentials resolved = DefaultCredentialsProvider.create().resolveCredentials();
      assertEquals(credentials.accessKeyId(), resolved.accessKeyId());
      assertEquals(credentials.secretAccessKey(), resolved.secretAccessKey());
      assertEquals(
          credentials instanceof AwsSessionCredentials, resolved instanceof AwsSessionCredentials);
      if (credentials instanceof AwsSessionCredentials) {
        assertEquals(
            ((AwsSessionCredentials) credentials).sessionToken(),
            ((AwsSessionCredentials) resolved).sessionToken());
      }
    }
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.putAll(exporter.headerSupplier.get());
    assertArrayEquals(PAYLOAD, exporter.data.get().toByteArray());
    if (credentials == null) {
      assertTrue(headers.isEmpty());
      return headers;
    }

    assertNotNull(headers.get("x-amz-date"));
    Instant signedAt =
        Instant.from(
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT)
                .withZone(ZoneOffset.UTC)
                .parse(headers.get("x-amz-date")));
    byte[] signedPayload = PAYLOAD;
    if (exporter.expectedCompression == CompressionMethod.GZIP) {
      ByteArrayOutputStream compressed = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
        gzip.write(PAYLOAD);
      }
      signedPayload = compressed.toByteArray();
    }
    byte[] expectedPayload = signedPayload;
    SignedRequest expected =
        AwsV4HttpSigner.create()
            .sign(
                builder ->
                    builder
                        .identity(credentials)
                        .request(
                            SdkHttpFullRequest.builder()
                                .method(SdkHttpMethod.POST)
                                .uri(URI.create(exporter.expectedEndpoint))
                                .putHeader("Content-Type", "application/x-protobuf")
                                .build())
                        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, exporter.serviceName)
                        .putProperty(AwsV4HttpSigner.REGION_NAME, exporter.expectedRegion)
                        .putProperty(
                            AwsV4HttpSigner.SIGNING_CLOCK, Clock.fixed(signedAt, ZoneOffset.UTC))
                        .payload(() -> new ByteArrayInputStream(expectedPayload)));
    Map<String, String> expectedHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    expected
        .request()
        .headers()
        .forEach((name, values) -> expectedHeaders.put(name, values.get(0)));
    assertEquals(expectedHeaders, headers);
    return headers;
  }

  private static Credentials roleCredentials() {
    return Credentials.builder()
        .accessKeyId(ACCESS_KEY)
        .secretAccessKey(SECRET_KEY)
        .sessionToken(SESSION_TOKEN)
        .expiration(EXPIRATION)
        .build();
  }

  private static Stream<Arguments> awsOtlpEndpoints() {
    return Stream.of(
        // Commercial partition.
        Arguments.of("https://xray.us-east-1.amazonaws.com/v1/traces", "us-east-1", "xray"),
        Arguments.of("https://xray.eu-west-1.amazonaws.com/v1/traces", "eu-west-1", "xray"),
        Arguments.of("https://logs.us-east-1.amazonaws.com/v1/logs", "us-east-1", "logs"),
        Arguments.of("https://logs.ap-southeast-2.amazonaws.com/v1/logs", "ap-southeast-2", "logs"),
        Arguments.of(
            "https://monitoring.us-east-1.amazonaws.com/v1/metrics", "us-east-1", "monitoring"),
        Arguments.of(
            "https://monitoring.eu-central-1.amazonaws.com/v1/metrics",
            "eu-central-1",
            "monitoring"),
        // China partition.
        Arguments.of("https://xray.cn-north-1.amazonaws.com.cn/v1/traces", "cn-north-1", "xray"),
        Arguments.of(
            "https://xray.cn-northwest-1.amazonaws.com.cn/v1/traces", "cn-northwest-1", "xray"),
        Arguments.of("https://logs.cn-north-1.amazonaws.com.cn/v1/logs", "cn-north-1", "logs"),
        Arguments.of(
            "https://logs.cn-northwest-1.amazonaws.com.cn/v1/logs", "cn-northwest-1", "logs"),
        Arguments.of(
            "https://monitoring.cn-north-1.amazonaws.com.cn/v1/metrics",
            "cn-north-1",
            "monitoring"),
        Arguments.of(
            "https://monitoring.cn-northwest-1.amazonaws.com.cn/v1/metrics",
            "cn-northwest-1",
            "monitoring"));
  }

  /** Isolates SDK credential settings, profile files, and metadata responses for each test. */
  private static final class AwsCredentialProviderHelper implements AutoCloseable {
    private final Map<String, String> originalProperties = new HashMap<>();
    private final Path credentialsFile;
    private final Path configFile;
    private final HttpServer server;
    private final DefaultCredentialsProvider provider;
    private final MockedStatic<DefaultCredentialsProvider> credentialsFactory;
    private final List<CredentialRequest> requests = new CopyOnWriteArrayList<>();

    private AwsCredentialProviderHelper(Path directory) throws Exception {
      for (SdkSystemSetting setting : SdkSystemSetting.values()) {
        isolateSetting(setting.property(), setting.environmentVariable());
      }
      for (ProfileFileSystemSetting setting : ProfileFileSystemSetting.values()) {
        isolateSetting(setting.property(), setting.environmentVariable());
      }
      credentialsFile = Files.write(directory.resolve("credentials"), new byte[0]);
      configFile = Files.write(directory.resolve("config"), new byte[0]);
      environment("AWS_SHARED_CREDENTIALS_FILE", credentialsFile.toString());
      environment("AWS_CONFIG_FILE", configFile.toString());
      environment("AWS_EC2_METADATA_DISABLED", "true");
      environment("AWS_REGION", "us-west-2");
      // Override only the SDK's environment lookup, including its SSO cache location.
      environment("HOME", directory.toString());
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::respond);
      server.start();
      environment("AWS_CONTAINER_SERVICE_ENDPOINT", origin());
      environment("AWS_EC2_METADATA_SERVICE_ENDPOINT", origin());
      provider =
          DefaultCredentialsProvider.builder()
              .asyncCredentialUpdateEnabled(false)
              .reuseLastProviderEnabled(false)
              .build();
      credentialsFactory = mockStatic(DefaultCredentialsProvider.class);
      credentialsFactory.when(DefaultCredentialsProvider::create).thenReturn(provider);
    }

    private void isolateSetting(String property, String environmentVariable) {
      if (property != null && !originalProperties.containsKey(property)) {
        originalProperties.put(property, System.getProperty(property));
        System.clearProperty(property);
      }
      if (environmentVariable != null) {
        environment(environmentVariable, null);
      }
    }

    private void environment(String name, String value) {
      SystemSettingUtilsTestBackdoor.addEnvironmentVariableOverride(name, value);
    }

    private void writeCredentials(String contents) throws Exception {
      Files.write(credentialsFile, contents.getBytes(StandardCharsets.UTF_8));
    }

    private void writeConfig(String contents) throws Exception {
      Files.write(configFile, contents.getBytes(StandardCharsets.UTF_8));
    }

    private String origin() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
      requests.add(new CredentialRequest(exchange));
      String path = exchange.getRequestURI().getPath();
      String response;
      if (exchange.getRequestMethod().equals("PUT") && path.equals("/latest/api/token")) {
        response = "test-imds-token";
      } else if (path.equals("/latest/meta-data/iam/security-credentials/")) {
        response = "test-role";
      } else {
        response =
            "{\"Code\":\"Success\",\"AccessKeyId\":\""
                + ACCESS_KEY
                + "\","
                + "\"SecretAccessKey\":\""
                + SECRET_KEY
                + "\",\"Token\":\""
                + SESSION_TOKEN
                + "\",\"Expiration\":\"2099-01-01T00:00:00Z\"}";
      }
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    }

    @Override
    public void close() {
      try {
        provider.close();
      } finally {
        credentialsFactory.close();
        server.stop(0);
        SystemSettingUtilsTestBackdoor.clearEnvironmentVariableOverrides();
        originalProperties.forEach(
            (name, value) -> {
              if (value == null) {
                System.clearProperty(name);
              } else {
                System.setProperty(name, value);
              }
            });
      }
    }
  }

  private static final class CredentialRequest {
    private final String method;
    private final String path;
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private CredentialRequest(HttpExchange exchange) {
      method = exchange.getRequestMethod();
      path = exchange.getRequestURI().getPath();
      exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
    }
  }

  private static final class TestExporter extends BaseOtlpAwsExporter {
    private final String expectedEndpoint;
    private final String expectedRegion;
    private final String serviceName;
    private final CompressionMethod expectedCompression;

    private TestExporter(
        String endpoint, String region, String serviceName, CompressionMethod compression) {
      super(endpoint, compression, null, serviceName, null);
      this.expectedEndpoint = endpoint;
      this.expectedRegion = region;
      this.serviceName = serviceName;
      this.expectedCompression = compression;
      ByteArrayOutputStream payload = new ByteArrayOutputStream();
      payload.write(PAYLOAD, 0, PAYLOAD.length);
      data.set(payload);
    }
  }
}
