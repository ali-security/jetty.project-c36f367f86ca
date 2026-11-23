//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Resource Handler test
 * 
 * TODO: increase the testing going on here
 */
public class ResourceHandlerTest
{
    private static String LN = System.getProperty("line.separator");
    private static Server _server;
    private static HttpConfiguration _config;
    private static ServerConnector _connector;
    private static LocalConnector _local;
    private static ContextHandler _contextHandler;
    private static ResourceHandler _resourceHandler;

    @BeforeClass
    public static void setUp() throws Exception
    {
        File dir = MavenTestingUtils.getTargetFile("test-classes/simple");
        File bigger = new File(dir,"bigger.txt");
        File big = new File(dir,"big.txt");
        try (OutputStream out = new FileOutputStream(bigger))
        {
            for (int i = 0; i < 100; i++)
            {
                try (InputStream in = new FileInputStream(big))
                {
                    IO.copy(in,out);
                }
            }
        }
        
        bigger.deleteOnExit();

        // determine how the SCM of choice checked out the big.txt EOL
        // we can't just use whatever is the OS default.
        // because, for example, a windows system using git can be configured for EOL handling using
        // local, remote, file lists, patterns, etc, rendering assumptions about the OS EOL choice
        // wrong for unit tests.
        LN = System.getProperty("line.separator");
        try (BufferedReader reader = Files.newBufferedReader(big.toPath(),StandardCharsets.UTF_8))
        {
            // a buffer large enough to capture at least 1 EOL
            char cbuf[] = new char[128];
            reader.read(cbuf);
            String sample = new String(cbuf);
            if (sample.contains("\r\n"))
            {
                LN = "\r\n";
            }
            else if (sample.contains("\n\r"))
            {
                LN = "\n\r";
            }
            else
            {
                LN = "\n";
            }
        }

        _server = new Server();
        _config = new HttpConfiguration();
        _config.setOutputBufferSize(2048);
        _connector = new ServerConnector(_server,new HttpConnectionFactory(_config));

        _local = new LocalConnector(_server);
        
        _server.setConnectors(new Connector[] { _connector, _local });

        _resourceHandler = new ResourceHandler();
        _resourceHandler.setMinAsyncContentLength(4096);
        _resourceHandler.setMinMemoryMappedContentLength(8192);

        _resourceHandler.setResourceBase(MavenTestingUtils.getTargetFile("test-classes/simple").getAbsolutePath());

        _contextHandler = new ContextHandler("/resource");
        _contextHandler.setHandler(_resourceHandler);
        _server.setHandler(_contextHandler);
        _server.start();
    }

    @AfterClass
    public static void tearDown() throws Exception
    {
        _server.stop();
    }

    @Before
    public void before()
    {
        _config.setOutputBufferSize(4096);
    }

    @Test
    public void testMissing() throws Exception
    {
        String rawResponse = _local.getResponse("GET /resource/jetty-dir.css HTTP/1.0\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        Assert.assertNotNull("missing jetty-dir.css",response.getContent());
    }

    @Test
    public void testSimple() throws Exception
    {
        String rawResponse = _local.getResponse("GET /resource/simple.txt HTTP/1.0\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals("simple text",response.getContent());
    }

    @Test
    public void testHeaders() throws Exception
    {
        String response = _local.getResponses("GET /resource/simple.txt HTTP/1.0\r\n\r\n");
        assertThat(response,startsWith("HTTP/1.1 200 OK"));
        assertThat(response,containsString("Content-Type: text/plain"));
        assertThat(response,containsString("Last-Modified: "));
        assertThat(response,containsString("Content-Length: 11"));
        assertThat(response,containsString("Server: Jetty"));
        assertThat(response,containsString("simple text"));
    }

    @Test
    public void testBigFile() throws Exception
    {
        _config.setOutputBufferSize(2048);
    
        String rawResponse = _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String content = response.getContent();
        
        assertThat(content,startsWith("     1\tThis is a big file"));
        assertThat(content,endsWith("   400\tThis is a big file" + LN));
    }

    @Test
    public void testBigFileBigBuffer() throws Exception
    {
        _config.setOutputBufferSize(16 * 1024);
        String rawResponse = _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String content = response.getContent();
        assertThat(content,startsWith("     1\tThis is a big file"));
        assertThat(content,endsWith("   400\tThis is a big file" + LN));
    }

    @Test
    public void testBigFileLittleBuffer() throws Exception
    {
        _config.setOutputBufferSize(8);
        String rawResponse = _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String content = response.getContent();
        assertThat(content,startsWith("     1\tThis is a big file"));
        assertThat(content,endsWith("   400\tThis is a big file" + LN));
    }

    @Test
    public void testBigger() throws Exception
    {
        try (Socket socket = new Socket("localhost",_connector.getLocalPort());)
        {
            socket.getOutputStream().write("GET /resource/bigger.txt HTTP/1.0\n\n".getBytes());
            Thread.sleep(1000);
            String response = IO.toString(socket.getInputStream());
            assertThat(response,startsWith("HTTP/1.1 200 OK"));
            assertThat(response,containsString("   400\tThis is a big file" + LN + "     1\tThis is a big file"));
            assertThat(response,endsWith("   400\tThis is a big file" + LN));
        }
    }
    
    @Test
    @Slow
    public void testSlowBiggest() throws Exception
    {
        _connector.setIdleTimeout(10000);
        
        File dir = MavenTestingUtils.getTargetFile("test-classes/simple");
        File biggest = new File(dir,"biggest.txt");
        try (OutputStream out = new FileOutputStream(biggest))
        {
            for (int i = 0; i < 10; i++)
            {
                try (InputStream in = new FileInputStream(new File(dir,"bigger.txt")))
                {
                    IO.copy(in,out);
                }
            }
            out.write("\nTHE END\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        biggest.deleteOnExit();
        
        try (Socket socket = new Socket("localhost",_connector.getLocalPort());OutputStream out=socket.getOutputStream();InputStream in=socket.getInputStream())
        {

            socket.getOutputStream().write("GET /resource/biggest.txt HTTP/1.0\n\n".getBytes());
            
            byte[] array = new byte[102400];
            ByteBuffer buffer=null;
            int i=0;
            while(true)
            {
                Thread.sleep(100);
                int len=in.read(array);
                if (len<0)
                    break;
                buffer=BufferUtil.toBuffer(array,0,len);
                // System.err.println(++i+": "+BufferUtil.toDetailString(buffer));
            }

            assertEquals('E',buffer.get(buffer.limit()-4));
            assertEquals('N',buffer.get(buffer.limit()-3));
            assertEquals('D',buffer.get(buffer.limit()-2));
            
        }
    }

    /**
     * Raw (non-encoded) request URIs that try to break out of the
     * Parent Directory href attribute of a directory listing.
     */
    private static final String[] LISTING_XSS_URIS =
    {
        "/listing/one/;\"onmouseover='alert(document.location)'",
        "/listing/one/;\"><script>alert(document.domain)<%2Fscript>"
    };

    private static File createListingDocroot() throws IOException
    {
        File docroot = MavenTestingUtils.getTargetTestingDir("ResourceHandlerTest-listingXSS");
        FS.ensureEmpty(docroot);
        FS.ensureDirExists(new File(docroot,"one"));
        FS.touch(new File(new File(docroot,"one"),"file.txt"));
        return docroot;
    }

    private static HttpTester.Response getListing(ResourceHandler handler, File docroot, String uri) throws Exception
    {
        Server server = new Server();
        LocalConnector local = new LocalConnector(server);
        server.setConnectors(new Connector[] { local });

        handler.setDirectoriesListed(true);
        handler.setResourceBase(docroot.getAbsolutePath());

        ContextHandler context = new ContextHandler("/listing");
        context.setHandler(handler);
        server.setHandler(context);
        server.start();
        try
        {
            return HttpTester.parseResponse(local.getResponse("GET " + uri + " HTTP/1.0\r\n\r\n"));
        }
        finally
        {
            server.stop();
        }
    }

    private static void assertListingNotInjected(HttpTester.Response response)
    {
        assertEquals(200,response.getStatus());
        String content = response.getContent();
        assertThat(content,containsString("Parent Directory"));
        assertThat(content,not(containsString("\"onmouseover")));
        assertThat(content,not(containsString("<script>")));
        assertThat(content,not(containsString("\"><")));
    }

    /**
     * CVE-2019-10241: the Parent Directory link of a directory listing must not
     * reflect the raw request URI into the href attribute.
     */
    @Test
    public void testListingXSS() throws Exception
    {
        File docroot = createListingDocroot();
        for (String uri : LISTING_XSS_URIS)
        {
            HttpTester.Response response = getListing(new ResourceHandler(),docroot,uri);
            assertListingNotInjected(response);
            assertThat(response.getContent(),containsString("file.txt"));
        }
    }

    /**
     * CVE-2019-10241: the ResourceHandler must hand an attribute-safe base URI to
     * {@link Resource#getListHTML(String, boolean)}, so the listing is safe even
     * when the Resource implementation renders the Parent Directory link from
     * the base URI as given (as unpatched jetty-util versions do).
     */
    @Test
    public void testListingXSSWithUnencodedParentLink() throws Exception
    {
        File docroot = createListingDocroot();
        for (String uri : LISTING_XSS_URIS)
        {
            ResourceHandler handler = new ResourceHandler()
            {
                @Override
                protected Resource getResource(HttpServletRequest request) throws MalformedURLException
                {
                    Resource resource = super.getResource(request);
                    if (!(resource instanceof PathResource) || !resource.isDirectory())
                        return resource;

                    return new PathResource(((PathResource)resource).getPath())
                    {
                        @Override
                        public String getListHTML(String base, boolean parent, String query) throws IOException
                        {
                            base = URIUtil.canonicalPath(base);
                            StringBuilder buf = new StringBuilder();
                            buf.append("<HTML><HEAD><TITLE>Unencoded Listing</TITLE></HEAD><BODY>\n");
                            if (parent)
                            {
                                buf.append("<A HREF=\"");
                                buf.append(URIUtil.addEncodedPaths(base,"../"));
                                buf.append("\">Parent Directory</A>\n");
                            }
                            buf.append("</BODY></HTML>\n");
                            return buf.toString();
                        }
                    };
                }
            };

            HttpTester.Response response = getListing(handler,docroot,uri);
            assertListingNotInjected(response);
            assertThat(response.getContent(),containsString("Unencoded Listing"));
            assertThat(response.getContent(),containsString("%22"));
        }
    }
}
