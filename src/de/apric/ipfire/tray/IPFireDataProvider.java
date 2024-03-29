/*
 * This file is part of IPFireTray.
 *
 * IPFireTray is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPFireTray is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPFireTray.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.apric.ipfire.tray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import sun.misc.BASE64Encoder;

/**
 *
 * @author apric
 */
public final class IPFireDataProvider {

    public static final String IPFIRE_SPEED_CGI_PATH = "/cgi-bin/speed.cgi"; // path to "speed.cgi"

    private final String host;
    private final int    port;
    private final String user;
    private final String pass;

    private final SSLSocketFactory sslSocketFactory;
    private final DocumentBuilder xmlDocBuilder;

    private long lastRefresh = System.currentTimeMillis();
    private long lastTotalDownKB = 0;
    private long lastTotalUpKB = 0;


    /**
     * create an IPFire-specific data provider
     *
     * @param host valid hostname
     * @param port valid port
     * @param user valud username (web interface admin)
     * @param pass valid password (web interface admin)
     * @throws Exception
     */
    public IPFireDataProvider(final String host, final int port, final String user, final String pass) throws Exception {

        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass;

        final SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, new TrustManager[] {
            new X509TrustManager() { // SSL trust manager (trusting even "invalid" certificates)
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
                public void checkClientTrusted(final X509Certificate[] xcs, final String string) throws CertificateException {}
                public void checkServerTrusted(final X509Certificate[] xcs, final String string) throws CertificateException {}
            }}, new java.security.SecureRandom());

        sslSocketFactory = sslContext.getSocketFactory();
        xmlDocBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    }


    /**
     * calculate the current down and up KB/s values by comparing the previous total values with the current ones
     *
     * @return float values for current down and up KB/s
     * @throws Exception
     */
    public float[] getSpeedParams() throws Exception {

        long totalDownKB        = lastTotalDownKB;
        long totalUpKB          = lastTotalUpKB;
        float currentDownKBpS   = -1.0f;
        float currentUpKBpS     = -1.0f;

        final String response = getContentFromSSLUrl();

        if (!response.isEmpty()) {

            final long[] totalUpDownValues = parseSpeedCgiXml(response);

            totalDownKB = totalUpDownValues[0];
            totalUpKB   = totalUpDownValues[1];

            final long currentTime = System.currentTimeMillis();

            if (lastTotalDownKB != 0 && lastTotalUpKB != 0) {
                currentDownKBpS = (totalDownKB - lastTotalDownKB)   * 1.0f / (currentTime - lastRefresh);
                currentUpKBpS   = (totalUpKB - lastTotalUpKB)       * 1.0f / (currentTime - lastRefresh);
            }

            lastRefresh = currentTime;
            lastTotalDownKB = totalDownKB;
            lastTotalUpKB = totalUpKB;
        }

        return new float[]{currentDownKBpS, currentUpKBpS};
    }


    /**
     * get HTTP body via SSL from IPFire (ignoring self-signed certificate!)
     * returns an empty string in case there are misc. Exceptions, but an illegal login will throw an IllegalArgumentException
     */
    protected String getContentFromSSLUrl() throws IOException {

        Socket sslSocket = null;
        PrintWriter outWriter = null;
        InputStream inStream = null;
        InputStreamReader inReader = null;
        BufferedReader buffReader = null;

        try {
            sslSocket = sslSocketFactory.createSocket(host, port);

            /* make request: */
            final String authStringBase64 = new BASE64Encoder().encode((user + ":" + pass).getBytes());
            outWriter = new PrintWriter(sslSocket.getOutputStream());
            outWriter.println("GET " + IPFIRE_SPEED_CGI_PATH + " HTTP/1.0");
            outWriter.println("HOST: " + host);
            outWriter.println("Authorization: BASIC " + authStringBase64);
            outWriter.println();
            outWriter.flush();

            /* response: */
            inStream = sslSocket.getInputStream();
            inReader = new InputStreamReader(inStream);
            buffReader = new BufferedReader(inReader);

            
            /* manually separate body from HTTP response: */
            String line = buffReader.readLine();
            final StringBuffer httpHeader = new StringBuffer();
            final StringBuffer httpBody = new StringBuffer();
            boolean isHttpHeaderOver = false;

            while (line != null) {
                if (isHttpHeaderOver) {
                    httpBody.append(line); // grab HTTP body
                }
                else {
                    httpHeader.append(line); // grab HTTP header
                }

                if (line.isEmpty()) {
                    isHttpHeaderOver = true; // first empty line separates HTTP header from body
                }

                line = buffReader.readLine();
            }

            /* check for unwanted HTTP responses: */
            if (httpHeader.toString().contains("401 Authorization Required")) {
                throw new IllegalArgumentException("Autorization failed! Please check the \"settings.properties\" and set a valid user/pass combination.");
            }

            return httpBody.toString();
        }
        catch (IllegalArgumentException e) {
            throw e; // only allow this kind of exception to be thrown
        }
        catch (Exception e) {
            return ""; // silent fail: empty string instead of HTTP body
        }
        finally {
            /* cleanup: */
            if (outWriter != null) {
                outWriter.close();
            }
            if (buffReader != null) {
                buffReader.close();
            }
            if (inReader != null) {
                inReader.close();
            }
            if (inStream != null) {
                inStream.close();
            }
            if (sslSocket != null) {
                sslSocket.close();
            }
        }
    }


    /**
     * parse an IPFire speed.cgi XML file for "total down KB" and "total up KB" values
     *
     * @param xmlString the XML file speed.cgi
     * @return long values for "total down KB" and "total up KB"
     * @throws Exception in case there was a problem reading the values
     */
    protected long[] parseSpeedCgiXml(final String xmlString) throws Exception {

        final Document doc = xmlDocBuilder.parse(new InputSource(new StringReader(xmlString)));

        final Element totalDownElement = (Element) doc.getElementsByTagName("rxb").item(0);
        final Element totalUpElement = (Element) doc.getElementsByTagName("txb").item(0);

        return new long[]{
            Long.parseLong(totalDownElement.getTextContent()), // total download in KB
            Long.parseLong(totalUpElement.getTextContent()) // total upload in KB
        };
    }

}
