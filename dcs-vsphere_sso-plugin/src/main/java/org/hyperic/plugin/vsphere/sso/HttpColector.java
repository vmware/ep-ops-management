package org.hyperic.plugin.vsphere.sso;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.hyperic.hq.agent.AgentKeystoreConfig;
import org.hyperic.hq.product.Collector;
import org.hyperic.hq.product.PluginException;
import static org.hyperic.plugin.vsphere.sso.DiscoveryVSphereSSO.readInputString;
import org.hyperic.util.http.HQHttpClient;
import org.hyperic.util.http.HttpConfig;

public class HttpColector extends Collector {

    private static final Log log = LogFactory.getLog(HttpColector.class);
    private static final AgentKeystoreConfig ksCFG = new AgentKeystoreConfig();
    protected HQHttpClient httpClient;
    protected String host;
    protected int port;
    protected boolean ssl;
    protected String path;
    protected String user;
    protected String pass;
    protected String pattern;

    @Override
    protected void init() throws PluginException {
        super.init();
        host = getProperties().getProperty("hostname");
        try {
            port = Integer.parseInt(getProperties().getProperty("port", "0"));
        } catch (Throwable e) {
            log.debug("[init] error parsing ''port': ", e);
        }
        ssl = "true".equalsIgnoreCase(getProperties().getProperty("ssl"));
        path = getProperties().getProperty("path");
        pattern = getProperties().getProperty("pattern");
    }

    @Override
    public void collect() {
        String hashCode = Integer.toHexString(hashCode());
        log.debug("[collect ("+ hashCode +")] start");

        HttpHost targetHost = new HttpHost(host, port, ssl ? "https" : "http");
        if (httpClient == null) {
            httpClient = new HQHttpClient(ksCFG, new HttpConfig(5000, 5000, null, 0),
                    ksCFG.isAcceptUnverifiedCert());
            log.debug("[collect ("+ hashCode +")] httpClient created: " + httpClient);
            if (StringUtils.isNotEmpty(user) && StringUtils.isNotEmpty(pass)) {
                httpClient.getCredentialsProvider().setCredentials(
                        new AuthScope(targetHost.getHostName(), targetHost.getPort()),
                        new UsernamePasswordCredentials(user, pass));
                log.debug("[collect ("+ hashCode +")] AuthScope created");
            }
        }

        HttpGet get = new HttpGet(targetHost.toURI() + path);
        log.debug("[collect ("+ hashCode +")] GET: " + get.getURI());
        try {
            long start = System.currentTimeMillis();
            HttpResponse response = httpClient.execute(get);
            long time = System.currentTimeMillis() - start;
            int statusCode = response.getStatusLine().getStatusCode();
            setValue("ResponseTime", (double) time);
            setValue("ResponseCode", (double) statusCode);

            String html = readInputString(response.getEntity().getContent());

            if (statusCode == HttpStatus.SC_OK) {
                log.debug("[collect (" + hashCode + ")] GET OK (" + statusCode + ") ResponseTime: " + time + "Ms.");
                if (pattern != null) {
                    Matcher m = Pattern.compile(pattern).matcher(html);;
                    if (m.find()) {
                        log.debug("[collect (" + hashCode + ")] pattern '" + pattern + "' found, AVAIL = 1");
                        setAvailability(true);
                    } else {
                        log.debug("[collect (" + hashCode + ")] pattern '" + pattern + "' NOT found, AVAIL = 0");
                        setAvailability(false);
                    }
                } else {
                    setAvailability(true);
                }
            } else {
                setAvailability(false);
                log.debug("[collect ("+ hashCode +")] GET failed: (" + statusCode + ") " + response.getStatusLine().getReasonPhrase());
            }
        } catch (IOException ex) {
            setAvailability(false);
            httpClient = null;
            log.debug("[collect ("+ hashCode +")] " + ex, ex);
        }

        log.debug("[collect ("+ hashCode +")] stop");
    }

    protected void analyzeResponse(HttpResponse response) throws IOException {
        EntityUtils.consume(response.getEntity());
    }
}
