/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.avatica.server;

import org.apache.calcite.avatica.AvaticaUtils;
import org.apache.calcite.avatica.ConnectionConfig;
import org.apache.calcite.avatica.ConnectionConfigImpl;
import org.apache.calcite.avatica.SpnegoTestUtil;
import org.apache.calcite.avatica.remote.AvaticaCommonsHttpClientImpl;
import org.apache.calcite.avatica.remote.CommonsHttpClientPoolCache;
import org.apache.calcite.avatica.util.SecurityUtils;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.client.KrbConfig;
import org.apache.kerby.kerberos.kerb.client.KrbConfigKey;
import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;

import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Test class for SPNEGO with Kerberos. Purely testing SPNEGO, not the Avatica "protocol" on top
 * of that HTTP. This variant of the test relies on the "feature" Avatica provides to not require
 * JAAS configuration by the user.
 */
public class HttpServerSpnegoWithoutJaasTest {
  private static final Logger LOG = LoggerFactory.getLogger(HttpServerSpnegoWithoutJaasTest.class);

  private static SimpleKdcServer kdc;
  private static HttpServer httpServer;

  private static KrbConfig clientConfig;

  private static int kdcPort;

  private static File clientKeytab;
  private static File serverKeytab;

  private static boolean isKdcStarted = false;
  private static boolean isHttpServerStarted = false;

  private static URI httpServerUri;

  @BeforeClass public static void setupKdc() throws Exception {
    kdc = new SimpleKdcServer();
    File target = SpnegoTestUtil.TARGET_DIR;
    assertTrue(target.exists());

    File kdcDir = new File(target, HttpServerSpnegoWithoutJaasTest.class.getSimpleName());
    if (kdcDir.exists()) {
      SpnegoTestUtil.deleteRecursively(kdcDir);
    }
    kdcDir.mkdirs();
    kdc.setWorkDir(kdcDir);

    kdc.setKdcHost(SpnegoTestUtil.KDC_HOST);
    kdcPort = SpnegoTestUtil.getFreePort();
    kdc.setAllowTcp(true);
    kdc.setAllowUdp(false);
    kdc.setKdcTcpPort(kdcPort);

    LOG.info("Starting KDC server at {}:{}", SpnegoTestUtil.KDC_HOST, kdcPort);

    kdc.init();
    kdc.start();
    isKdcStarted = true;

    try (FileInputStream fis = new FileInputStream(new File(kdcDir, "krb5.conf"));
        InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
        BufferedReader r = new BufferedReader(isr)) {
      String line;
      while ((line = r.readLine()) != null) {
        LOG.debug("KRB5 Config line: {}", line);
      }
    }

    File keytabDir = new File(target, HttpServerSpnegoWithoutJaasTest.class.getSimpleName()
        + "_keytabs");
    if (keytabDir.exists()) {
      SpnegoTestUtil.deleteRecursively(keytabDir);
    }
    keytabDir.mkdirs();
    setupUsers(keytabDir);

    clientConfig = new KrbConfig();
    clientConfig.setString(KrbConfigKey.KDC_HOST, SpnegoTestUtil.KDC_HOST);
    clientConfig.setInt(KrbConfigKey.KDC_TCP_PORT, kdcPort);
    clientConfig.setString(KrbConfigKey.DEFAULT_REALM, SpnegoTestUtil.REALM);

    // Kerby sets "java.security.krb5.conf" for us!
    System.clearProperty("java.security.auth.login.config");
    System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");

    // Create and start an HTTP server configured only to allow SPNEGO requests
    // We use `withAutomaticLogin(File)` here which should invalidate the need to do JAAS config
    httpServer = new HttpServer.Builder()
        .withPort(0)
        .withAutomaticLogin(serverKeytab)
        .withSpnego(SpnegoTestUtil.SERVER_PRINCIPAL, SpnegoTestUtil.REALM)
        .withHandler(new SpnegoTestUtil.AuthenticationRequiredAvaticaHandler())
        .build();
    httpServer.start();
    isHttpServerStarted = true;

    httpServerUri = new URI("http://" + SpnegoTestUtil.KDC_HOST + ":" + httpServer.getPort());
    LOG.info("HTTP server running at {}", httpServerUri);
  }

  @AfterClass public static void stopKdc() throws Exception {
    if (isHttpServerStarted) {
      LOG.info("Stopping HTTP server at {}", httpServerUri);
      httpServer.stop();
    }

    if (isKdcStarted) {
      LOG.info("Stopping KDC on {}", kdcPort);
      kdc.stop();
    }
  }

  private static void setupUsers(File keytabDir) throws KrbException {
    String clientPrincipal = SpnegoTestUtil.CLIENT_PRINCIPAL.substring(0,
        SpnegoTestUtil.CLIENT_PRINCIPAL.indexOf('@'));
    clientKeytab = new File(keytabDir, clientPrincipal.replace('/', '_') + ".keytab");
    if (clientKeytab.exists()) {
      SpnegoTestUtil.deleteRecursively(clientKeytab);
    }
    LOG.info("Creating {} with keytab {}", clientPrincipal, clientKeytab);
    SpnegoTestUtil.setupUser(kdc, clientKeytab, clientPrincipal);

    String serverPrincipal = SpnegoTestUtil.SERVER_PRINCIPAL.substring(0,
        SpnegoTestUtil.SERVER_PRINCIPAL.indexOf('@'));
    serverKeytab = new File(keytabDir, serverPrincipal.replace('/', '_') + ".keytab");
    if (serverKeytab.exists()) {
      SpnegoTestUtil.deleteRecursively(serverKeytab);
    }
    LOG.info("Creating {} with keytab {}", SpnegoTestUtil.SERVER_PRINCIPAL, serverKeytab);
    SpnegoTestUtil.setupUser(kdc, serverKeytab, SpnegoTestUtil.SERVER_PRINCIPAL);
  }

  @Test public void testNormalClientsDisallowed() throws Exception {
    LOG.info("Connecting to {}", httpServerUri.toString());
    HttpURLConnection conn = (HttpURLConnection) httpServerUri.toURL().openConnection();
    conn.setRequestMethod("GET");
    // Authentication should fail because we didn't provide anything
    assertEquals(401, conn.getResponseCode());
  }

  @Test public void testServerVersionNotReturnedForUnauthorisedAccess() throws Exception {
    LOG.info("Connecting to {}", httpServerUri.toString());
    HttpURLConnection conn = (HttpURLConnection) httpServerUri.toURL().openConnection();
    conn.setRequestMethod("GET");
    assertEquals("Unauthorized response status code", 401, conn.getResponseCode());
    assertNull("Server information was not expected", conn.getHeaderField("server"));
  }

  @Test public void testAuthenticatedClientsAllowed() throws Exception {
    // Create the subject for the client
    final Subject clientSubject = AvaticaJaasKrbUtil.loginUsingKeytab(
        SpnegoTestUtil.CLIENT_PRINCIPAL, clientKeytab);
    final Set<Principal> clientPrincipals = clientSubject.getPrincipals();
    // Make sure the subject has a principal
    assertFalse(clientPrincipals.isEmpty());

    // Get a TGT for the subject (might have many, different encryption types). The first should
    // be the default encryption type.
    Set<KerberosTicket> privateCredentials =
            clientSubject.getPrivateCredentials(KerberosTicket.class);
    assertFalse(privateCredentials.isEmpty());
    KerberosTicket tgt = privateCredentials.iterator().next();
    assertNotNull(tgt);
    LOG.info("Using TGT with etype: {}", tgt.getSessionKey().getAlgorithm());

    // The name of the principal
    final String principalName = clientPrincipals.iterator().next().getName();

    // Run this code, logged in as the subject (the client)
    byte[] response = SecurityUtils.callAs(clientSubject, new Callable<byte[]>() {
      @Override public byte[] call() throws Exception {
        // Logs in with Kerberos via GSS
        GSSManager gssManager = GSSManager.getInstance();
        Oid oid = new Oid(SpnegoTestUtil.JGSS_KERBEROS_TICKET_OID);
        GSSName gssClient = gssManager.createName(principalName, GSSName.NT_USER_NAME);
        GSSCredential credential = gssManager.createCredential(gssClient,
            GSSCredential.DEFAULT_LIFETIME, oid, GSSCredential.INITIATE_ONLY);

        Properties props = new Properties();
        ConnectionConfig config = new ConnectionConfigImpl(props);

        PoolingHttpClientConnectionManager pool = CommonsHttpClientPoolCache.getPool(config);

        // Passes the GSSCredential into the HTTP client implementation
        final AvaticaCommonsHttpClientImpl httpClient =
            new AvaticaCommonsHttpClientImpl(httpServerUri);
        httpClient.setHttpClientPool(pool, config);
        httpClient.setGSSCredential(credential);

        return httpClient.send(new byte[0]);
      }
    });

    // We should get a response which is "OK" with our client's name
    assertNotNull(response);
    assertEquals("OK " + SpnegoTestUtil.CLIENT_NAME,
        AvaticaUtils.newStringUtf8(response));
  }
}

// End HttpServerSpnegoWithoutJaasTest.java
