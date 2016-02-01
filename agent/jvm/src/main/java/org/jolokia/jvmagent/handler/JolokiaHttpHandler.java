package org.jolokia.jvmagent.handler;

/*
 * Copyright 2009-2013 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MalformedObjectNameException;
import javax.management.RuntimeMBeanException;
import javax.security.auth.Subject;

import com.sun.net.httpserver.*;
import org.jolokia.jvmagent.ParsedUri;
import org.jolokia.server.core.config.ConfigKey;
import org.jolokia.server.core.config.Configuration;
import org.jolokia.server.core.http.BackChannelHolder;
import org.jolokia.server.core.http.HttpRequestHandler;
import org.jolokia.server.core.request.EmptyResponseException;
import org.jolokia.server.core.service.api.JolokiaContext;
import org.jolokia.server.core.service.api.LogHandler;
import org.jolokia.service.discovery.DiscoveryMulticastResponder;
import org.json.simple.JSONAware;

/**
 * HttpHandler for handling a Jolokia request
 *
 * @author roland
 * @since Mar 3, 2010
 */
public class JolokiaHttpHandler implements HttpHandler {

    // The HttpRequestHandler
    private HttpRequestHandler requestHandler;

    // Context of this request
    private String contextPath;

    // Content type matching
    private Pattern contentTypePattern = Pattern.compile(".*;\\s*charset=([^;,]+)\\s*.*");

    // Global context
    private JolokiaContext jolokiaContext;

    // Backchannel Thread Pool (TODO: Optimize that for backchannel handling)
    private Executor backChannelThreadPool = Executors.newCachedThreadPool();
    /**
     * Create a new HttpHandler for processing HTTP request
     *
     * @param pJolokiaContext jolokia context
     */
    public JolokiaHttpHandler(JolokiaContext pJolokiaContext) {
        jolokiaContext = pJolokiaContext;

        contextPath = jolokiaContext.getConfig(ConfigKey.AGENT_CONTEXT);
        if (!contextPath.endsWith("/")) {
            contextPath += "/";
        }

        requestHandler = new HttpRequestHandler(jolokiaContext);
    }
     /**
     * Handler a request. If the handler is not yet started, an exception is thrown
     *
     * @param pHttpExchange the request/response object
     * @throws IOException if something fails during handling
     * @throws IllegalStateException if the handler has not yet been started
     */
    public void handle(final HttpExchange pHttpExchange) throws IOException {
        try {
            checkAuthentication(pHttpExchange);

            Subject subject = (Subject) pHttpExchange.getAttribute(ConfigKey.JAAS_SUBJECT_REQUEST_ATTRIBUTE);
            if (subject != null)  {
                doHandleAs(subject, pHttpExchange);
            }  else {
                doHandle(pHttpExchange);
            }
        } catch (SecurityException exp) {
            sendForbidden(pHttpExchange,exp);
        }
    }

    // run as priviledged action
    private void doHandleAs(Subject subject, final HttpExchange pHttpExchange) {
        try {
            Subject.doAs(subject, new PrivilegedExceptionAction<Void>() {
            public Void run() throws IOException {
                doHandle(pHttpExchange);
                return null;
            }
            });
        } catch (PrivilegedActionException e) {
            throw new SecurityException("Security exception: " + e.getCause(),e.getCause());
        }
    }

    /**
     * Protocol based authentication checks called very early and before handling a request.
     * If the check fails a security exception must be thrown
     *
     * The default implementation does nothing and should be overridden for a valid check.
     *
     * @param pHttpExchange exchange to check
     * @throws SecurityException if check fails.
     */
    protected void checkAuthentication(HttpExchange pHttpExchange) throws SecurityException { }

    /**
     * Handler a request. If the handler is not yet started, an exception is thrown
     *
     * @param pExchange the request/response object
     * @throws IOException if something fails during handling
     * @throws IllegalStateException if the handler has not yet been started
     */
    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.AvoidInstanceofChecksInCatchClause"})
    public void doHandle(HttpExchange pExchange) throws IOException {
        JSONAware json = null;
        URI uri = pExchange.getRequestURI();
        ParsedUri parsedUri = new ParsedUri(uri, contextPath);
        try {
            // Set back channel
            prepareBackChannel(pExchange);

            // Check access policy
            InetSocketAddress address = pExchange.getRemoteAddress();
            requestHandler.checkAccess(getHostName(address),
                                       address.getAddress().getHostAddress(),
                                       extractOriginOrReferer(pExchange));
            String method = pExchange.getRequestMethod();

            // Dispatch for the proper HTTP request method
            if ("GET".equalsIgnoreCase(method)) {
                setHeaders(pExchange);
                json = executeGetRequest(parsedUri);
            } else if ("POST".equalsIgnoreCase(method)) {
                setHeaders(pExchange);
                json = executePostRequest(pExchange, parsedUri);
            } else if ("OPTIONS".equalsIgnoreCase(method)) {
                performCorsPreflightCheck(pExchange);
            } else {
                throw new IllegalArgumentException("HTTP Method " + method + " is not supported.");
            }
            if (jolokiaContext.isDebug()) {
                jolokiaContext.info("Response: " + json);
            }
        } catch (EmptyResponseException exp) {
            // No response needed, will answer later ..
            return;
        } catch (Throwable exp) {
            json = requestHandler.handleThrowable(
                    exp instanceof RuntimeMBeanException ? ((RuntimeMBeanException) exp).getTargetException() : exp);
        } finally {
            releaseBackChannel();
        }
        sendResponse(pExchange, parsedUri, json);
    }

    private void prepareBackChannel(HttpExchange pExchange) {
        BackChannelHolder.set(new HttpExchangeBackChannel(pExchange,backChannelThreadPool));
    }

    private void releaseBackChannel() {
        BackChannelHolder.remove();
    }


    // ========================================================================

    // Used for checking origin or referer is an origin policy is enabled
    private String extractOriginOrReferer(HttpExchange pExchange) {
        Headers headers = pExchange.getRequestHeaders();
        String origin = headers.getFirst("Origin");
        if (origin == null) {
            origin = headers.getFirst("Referer");
        }
        return origin != null ? origin.replaceAll("[\\n\\r]*","") : null;
    }

    // Return hostname of given address, but only when reverse DNS lookups are allowed
    private String getHostName(InetSocketAddress address) {
        return configuration.getAsBoolean(ConfigKey.ALLOW_DNS_REVERSE_LOOKUP) ? address.getHostName() : null;
    }

    private JSONAware executeGetRequest(ParsedUri parsedUri) throws EmptyResponseException {
        return requestHandler.handleGetRequest(parsedUri.getUri().toString(),parsedUri.getPathInfo(), parsedUri.getParameterMap());
    }

    private JSONAware executePostRequest(HttpExchange pExchange, ParsedUri pUri) throws MalformedObjectNameException, IOException, EmptyResponseException {
        String encoding = null;
        Headers headers = pExchange.getRequestHeaders();
        String cType =  headers.getFirst("Content-Type");
        if (cType != null) {
            Matcher matcher = contentTypePattern.matcher(cType);
            if (matcher.matches()) {
                encoding = matcher.group(1);
            }
        }
        InputStream is = pExchange.getRequestBody();
        return requestHandler.handlePostRequest(pUri.toString(),is, encoding, pUri.getParameterMap());
    }

    private void performCorsPreflightCheck(HttpExchange pExchange) {
        Headers requestHeaders = pExchange.getRequestHeaders();
        Map<String,String> respHeaders =
                requestHandler.handleCorsPreflightRequest(requestHeaders.getFirst("Origin"),
                                                          requestHeaders.getFirst("Access-Control-Request-Headers"));
        Headers responseHeaders = pExchange.getResponseHeaders();
        for (Map.Entry<String,String> entry : respHeaders.entrySet()) {
            responseHeaders.set(entry.getKey(), entry.getValue());
        }
    }

    private void setHeaders(HttpExchange pExchange) {
        String origin = requestHandler.extractCorsOrigin(pExchange.getRequestHeaders().getFirst("Origin"));
        Headers headers = pExchange.getResponseHeaders();
        if (origin != null) {
            headers.set("Access-Control-Allow-Origin",origin);
            headers.set("Access-Control-Allow-Credentials","true");
        }

        // Avoid caching at all costs
        headers.set("Cache-Control", "no-cache");
        headers.set("Pragma","no-cache");

        // Check for a date header and set it accordingly to the recommendations of
        // RFC-2616. See also {@link AgentServlet#setNoCacheHeaders()}
        // Issue: #71
        Calendar cal = Calendar.getInstance();
        headers.set("Date",formatHeaderDate(cal.getTime()));
        // 1h  in the past since it seems, that some servlet set the date header on their
        // own so that it cannot be guaranteed that these headers are really equals.
        // It happened on Tomcat that "Date:" was finally set *before* "Expires:" in the final
        // answers sometimes which seems to be an implementation peculiarity from Tomcat
        cal.add(Calendar.HOUR, -1);
        headers.set("Expires",formatHeaderDate(cal.getTime()));
    }

    private void sendForbidden(HttpExchange pExchange, SecurityException securityException) throws IOException {
        String response = "403 (Forbidden)\n";
        if (securityException != null && securityException.getMessage() != null) {
            response += "\n" + securityException.getMessage() + "\n";
        }
        pExchange.sendResponseHeaders(403, response.length());
        OutputStream os = pExchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    private void sendResponse(HttpExchange pExchange, ParsedUri pParsedUri, JSONAware pJson) throws IOException {
        OutputStream out = null;
        try {
            Headers headers = pExchange.getResponseHeaders();
            if (pJson != null) {
                headers.set("Content-Type", getMimeType(pParsedUri) + "; charset=utf-8");
                String json = pJson.toJSONString();
                String callback = pParsedUri.getParameter(ConfigKey.CALLBACK.getKeyValue());
                String content = callback == null ? json : callback + "(" + json + ");";
                byte[] response = content.getBytes("UTF8");
                pExchange.sendResponseHeaders(200,response.length);
                out = pExchange.getResponseBody();
                out.write(response);
            } else {
                headers.set("Content-Type", "text/plain");
                pExchange.sendResponseHeaders(200,-1);
            }
        } finally {
            if (out != null) {
                // Always close in order to finish the request.
                // Otherwise the thread blocks.
                out.close();
            }
        }
    }

    // Get the proper mime type according to configuration
    private String getMimeType(ParsedUri pParsedUri) {
        if (pParsedUri.getParameter(ConfigKey.CALLBACK.getKeyValue()) != null) {
            return "text/javascript";
        } else {
            String mimeType = pParsedUri.getParameter(ConfigKey.MIME_TYPE.getKeyValue());
            if (mimeType != null) {
                return mimeType;
            }
            mimeType = jolokiaContext.getConfig(ConfigKey.MIME_TYPE);
            return mimeType != null ? mimeType : ConfigKey.MIME_TYPE.getDefaultValue();
        }
    }


    private String formatHeaderDate(Date date) {
        DateFormat rfc1123Format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        rfc1123Format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return rfc1123Format.format(date);
    }
}
