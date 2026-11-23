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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.Cookie;

import org.junit.Test;

/**
 * Tests of the {@link CookieCutter} handling of a ';' within a quoted cookie value,
 * which is rejected to prevent cookie smuggling (CVE-2023-26049, GHSA-p26g-97m4-6q7c).
 */
public class CookieCutterTest
{
    private static Cookie[] parseCookieHeaders(String... headers)
    {
        CookieCutter cutter = new CookieCutter();
        for (String header : headers)
            cutter.addCookieField(header);
        return cutter.getCookies();
    }

    private static List<String> toNameValues(Cookie[] cookies)
    {
        List<String> nameValues = new ArrayList<>();
        for (Cookie cookie : cookies)
            nameValues.add(cookie.getName() + "=" + cookie.getValue());
        return nameValues;
    }

    private static void assertCookies(String header, String... expected)
    {
        assertThat("Cookies of <" + header + ">", toNameValues(parseCookieHeaders(header)), is(Arrays.asList(expected)));
    }

    /**
     * A ';' within a quoted value must not let that cookie absorb the cookies that follow it.
     */
    @Test
    public void testSemicolonInQuotedValueRejected()
    {
        assertCookies("A=\"1; B=2\"; C=3", "C=3");
        assertCookies("A=\"1; B=2; C=3");
        assertCookies("A=\"1\\; B=2\"; C=3", "C=3");
        assertCookies("A=\"1;\"; B=2; C=3", "B=2", "C=3");
        assertCookies("SESSION=1234; other=\"quoted=;HttpOnly_cookie=secret\"", "SESSION=1234");
        assertCookies("name=value; other=\"quoted=;value\"", "name=value");
        assertCookies("\"a;b\"=c; C=3", "C=3");
        // the rejection of a cookie without a name must not be inherited by the cookie that follows it
        assertCookies("=\"a;b\"; C=3", "C=3");

        // the rejection applies only to the cookie field it was found in
        assertThat(toNameValues(parseCookieHeaders("A=\"1; B=2\"", "C=3")), is(Arrays.asList("C=3")));
    }

    /**
     * The $Path/$Domain attributes following a rejected cookie must not be applied to the cookie before it,
     * and a rejected attribute must not be applied at all.
     */
    @Test
    public void testRejectedCookieAttributesNotApplied()
    {
        Cookie[] cookies = parseCookieHeaders("$Version=1; A=1; $Path=/a; B=\"x;y\"; $Path=/b; $Domain=example.com");
        assertThat(toNameValues(cookies), is(Arrays.asList("A=1")));
        assertThat(cookies[0].getPath(), is("/a"));
        assertNull(cookies[0].getDomain());

        cookies = parseCookieHeaders("A=1; $Path=\"/x; B=2\"");
        assertThat(toNameValues(cookies), is(Arrays.asList("A=1")));
        assertNull(cookies[0].getPath());
    }

    /**
     * CVE-2023-26049 / GHSA-p26g-97m4-6q7c: an attacker controlled cookie with a quoted value
     * must not smuggle the value of a following (possibly HttpOnly) cookie.
     */
    @Test
    public void testCookieSmuggling()
    {
        String[] inputs = {
            // The advisory example
            "DISPLAY_LANGUAGE=\"b; JSESSIONID=1337; c=d\"",
            "DISPLAY_LANGUAGE=\"b; JSESSIONID=1337\"; c=d",
            // Smuggled cookie last, so the quote is never terminated
            "DISPLAY_LANGUAGE=\"b; JSESSIONID=1337",
            // The ';' separator escaped by a trailing backslash in the attacker controlled value
            "DISPLAY_LANGUAGE=\"b\\; JSESSIONID=1337",
            "DISPLAY_LANGUAGE=\"b\\; JSESSIONID=1337\"",
            "DISPLAY_LANGUAGE=\"b\\; JSESSIONID=1337; c=d\"",
            // A cookie without a name must not pass its value on to the cookie that follows it
            "=\"b; JSESSIONID=1337\"; c=\"d\"x"
        };

        for (String input : inputs)
        {
            for (Cookie cookie : parseCookieHeaders(input))
            {
                assertThat("Cookie.name of <" + input + ">", cookie.getName(), not(is("DISPLAY_LANGUAGE")));
                assertThat("Cookie.value of <" + input + ">", cookie.getValue(), not(containsString("JSESSIONID")));
                assertThat("Cookie.value of <" + input + ">", cookie.getValue(), not(containsString("1337")));
            }
        }
    }

    /**
     * Cookies without a ';' within a quoted value are parsed as before.
     */
    @Test
    public void testUnchangedCookies()
    {
        assertCookies("A=1; B=2; C=3", "A=1", "B=2", "C=3");
        assertCookies("A=a;b; C=3", "A=a", "b=", "C=3");
        assertCookies("A=\"1\"; B=2; C=3", "A=1", "B=2", "C=3");
        assertCookies("A=\"1 B=2\"; C=3", "A=1 B=2", "C=3");
        assertCookies("A=\"1,\"; B=2; C=3", "A=1,", "B=2", "C=3");
        assertCookies("A=1,2; B=3", "A=1,2", "B=3");
        assertCookies("A=\"\\1\"; B=2; C=3", "A=1", "B=2", "C=3");
        assertCookies("A=\"x\\\"y\"; B=2", "A=x\"y", "B=2");
        assertCookies("name=quoted=\"\\\"badly\\\"\"", "name=quoted=\"badly\"");
        assertCookies("__utmz=14316.133020.1.1.utr=gna.de|ucn=(real)|utd=reral|utct=/games/hen-one,gnt-50-ba-keys:key,2072262.html",
            "__utmz=14316.133020.1.1.utr=gna.de|ucn=(real)|utd=reral|utct=/games/hen-one,gnt-50-ba-keys:key,2072262.html");
        assertCookies("x=\"abc");

        Cookie[] cookies = parseCookieHeaders("$Version=1; A=1; $Path=/a; $Domain=example.com; B=\"2\"; $Path=/b");
        assertThat(toNameValues(cookies), is(Arrays.asList("A=1", "B=2")));
        assertThat(cookies[0].getPath(), is("/a"));
        assertThat(cookies[0].getDomain(), is("example.com"));
        assertThat(cookies[0].getVersion(), is(1));
        assertThat(cookies[1].getPath(), is("/b"));
    }
}
