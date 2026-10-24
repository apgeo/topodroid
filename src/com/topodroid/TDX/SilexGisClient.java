/* @file SilexGisClient.java
 *
 * @author apgeo
 * @date oct 2026
 *
 * @brief TopoDroid client of a SilexGIS server (HTTP, JSON)
 *
 * Every call carries the personal access key as "Authorization: Bearer sgx_...".
 * The class uses only java.net and org.json, without android classes, so that it
 * can be tested on the JVM.
 * --------------------------------------------------------
 *  Copyright This software is distributed under GPL-3.0 or later
 *  See the file COPYING.
 * --------------------------------------------------------
 */
package com.topodroid.TDX;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.MalformedURLException;

import java.util.Locale;

import org.json.JSONObject;
import org.json.JSONException;

public class SilexGisClient
{
  public static final int CONTRACT_VERSION = 1; // survey-upload contract version, sent with every call

  static final int CONNECT_TIMEOUT = 15000; // [ms]
  static final int READ_TIMEOUT    = 30000; // [ms]

  // base-URL check
  public static final int URL_OK        = 0;
  public static final int URL_EMPTY     = 1; // no URL
  public static final int URL_INVALID   = 2; // not a http(s) URL
  public static final int URL_CLEARTEXT = 3; // http to a host that is not local

  // result kinds
  public static final int OK               = 0; // 2xx with a JSON object
  public static final int NOT_CONFIGURED   = 1; // no URL or no key
  public static final int BAD_URL          = 2; // URL refused (see the url field of the result)
  public static final int KEY_REFUSED      = 3; // 401: the access key is not valid (revoked, expired, mistyped)
  public static final int KEY_NOT_ACCEPTED = 4; // 403 auth.access_key_not_accepted: the route does not accept an access key
  public static final int PROBLEM          = 5; // any other refusal, with the Problem Details of the server
  public static final int NETWORK          = 6; // the server could not be reached
  public static final int BAD_RESPONSE     = 7; // the answer is not a JSON object

  public static final String CODE_KEY_NOT_ACCEPTED = "auth.access_key_not_accepted";

  /** result of a call
   */
  public static class Result
  {
    public final int kind;
    public final int status;      // HTTP status (0 if there was no answer)
    public final String code;     // Problem Details "code" (null if none)
    public final String title;    // Problem Details "title" (null if none)
    public final String detail;   // Problem Details "detail", or the network error (null if none)
    public final JSONObject json; // answer (only if kind is OK)
    public final int url;         // base-URL check (only if kind is BAD_URL)

    Result( int k, int s, String c, String t, String d, JSONObject j, int u )
    {
      kind   = k;
      status = s;
      code   = c;
      title  = t;
      detail = d;
      json   = j;
      url    = u;
    }

    /** @return true if the call succeeded
     */
    public boolean isOk() { return kind == OK; }

    /** @return a short text of the refusal, for the log or a toast: detail, else title, else code
     */
    public String message()
    {
      if ( detail != null && ! detail.isEmpty() ) return detail;
      if ( title  != null && ! title.isEmpty()  ) return title;
      if ( code   != null ) return code;
      return ( status > 0 )? ( "HTTP " + status ) : "";
    }

    /** @return a string member of the answer, or the default
     * @param name  member name
     * @param dflt  default value
     */
    public String getString( String name, String dflt ) { return ( json == null )? dflt : json.optString( name, dflt ); }
  }

  private final String mBaseUrl; // normalized base URL
  private final String mKey;     // access key
  private final int mUrlCheck;

  /** cstr
   * @param base_url  server base URL, e.g. "https://silexgis.example.org"
   * @param key       personal access key ("sgx_...")
   */
  public SilexGisClient( String base_url, String key )
  {
    mBaseUrl  = normalizeBaseUrl( base_url );
    mKey      = ( key == null )? "" : key.trim();
    mUrlCheck = checkBaseUrl( mBaseUrl );
  }

  /** @return the normalized base URL
   */
  public String getBaseUrl() { return mBaseUrl; }

  /** @return the base URL without surrounding blanks and without trailing slashes
   * @param url   base URL
   */
  public static String normalizeBaseUrl( String url )
  {
    if ( url == null ) return "";
    url = url.trim();
    while ( url.endsWith( "/" ) ) url = url.substring( 0, url.length() - 1 );
    return url;
  }

  /** @return the check of a base URL: URL_OK, URL_EMPTY, URL_INVALID, or URL_CLEARTEXT
   * @param url   base URL
   * @note http is accepted only towards the emulator host alias and the loopback, for development
   */
  public static int checkBaseUrl( String url )
  {
    url = normalizeBaseUrl( url );
    if ( url.isEmpty() ) return URL_EMPTY;
    try {
      URL u = new URL( url );
      String protocol = u.getProtocol().toLowerCase( Locale.US );
      String host = u.getHost();
      if ( host == null || host.isEmpty() ) return URL_INVALID;
      if ( protocol.equals( "https" ) ) return URL_OK;
      if ( ! protocol.equals( "http" ) ) return URL_INVALID;
      host = host.toLowerCase( Locale.US );
      if ( host.equals( "10.0.2.2" ) || host.equals( "127.0.0.1" ) || host.equals( "localhost" ) ) return URL_OK;
      return URL_CLEARTEXT;
    } catch ( MalformedURLException e ) {
      return URL_INVALID;
    }
  }

  /** @return true if both the base URL and the access key are set
   */
  public boolean isConfigured() { return ! mBaseUrl.isEmpty() && ! mKey.isEmpty(); }

  /** @return the access key masked for display: the first eight characters followed by an ellipsis
   * @param key   access key
   */
  public static String maskKey( String key )
  {
    if ( key == null ) return "";
    key = key.trim();
    return ( key.length() <= 8 )? key : key.substring( 0, 8 ) + "…";
  }

  // ---------------------------------------------------------------------
  // calls

  /** @return the account of the access key: GET /api/v1/me
   */
  public Result me() { return request( "GET", "/api/v1/me", null ); }

  /** @return the server name and version: GET /api/v1/about
   */
  public Result about() { return request( "GET", "/api/v1/about", null ); }

  /** @return what the server accepts and produces: GET /api/v1/survey-uploads/capabilities
   */
  public Result capabilities() { return request( "GET", "/api/v1/survey-uploads/capabilities", null ); }

  /** @return whether the server compiles the surveys, from the answer of capabilities()
   * @param caps   capabilities result
   */
  public static boolean compilerAvailable( Result caps )
  {
    if ( caps == null || caps.json == null ) return false;
    JSONObject compiler = caps.json.optJSONObject( "compiler" );
    return compiler != null && compiler.optBoolean( "available", false );
  }

  /** @return the account label from the answer of me(): display name (or user name) and e-mail
   * @param me   me result
   */
  public static String accountLabel( Result me )
  {
    if ( me == null || me.json == null ) return "";
    String name = me.json.optString( "displayName", "" );
    if ( name.isEmpty() || name.equals( "null" ) ) name = me.json.optString( "userName", "" );
    String email = me.json.optString( "email", "" );
    if ( email.isEmpty() || email.equals( "null" ) ) return name;
    return name.isEmpty()? email : ( name + " <" + email + ">" );
  }

  /** perform a call and read the JSON answer
   * @param method   HTTP method
   * @param path     route path, starting with "/"
   * @param body     JSON body (null for none)
   * @return the result of the call
   */
  public Result request( String method, String path, JSONObject body )
  {
    if ( ! isConfigured() ) return new Result( NOT_CONFIGURED, 0, null, null, null, null, mUrlCheck );
    if ( mUrlCheck != URL_OK ) return new Result( BAD_URL, 0, null, null, null, null, mUrlCheck );
    HttpURLConnection http = null;
    try {
      URL url = new URL( mBaseUrl + path );
      http = (HttpURLConnection) url.openConnection();
      http.setConnectTimeout( CONNECT_TIMEOUT );
      http.setReadTimeout( READ_TIMEOUT );
      http.setInstanceFollowRedirects( false ); // a redirect could move the key to another host
      http.setUseCaches( false );
      http.setRequestMethod( method );
      http.setRequestProperty( "Authorization", "Bearer " + mKey );
      http.setRequestProperty( "Accept", "application/json, application/problem+json" );
      http.setRequestProperty( "X-SilexGis-Contract", Integer.toString( CONTRACT_VERSION ) );
      if ( body != null ) {
        byte[] bytes = body.toString().getBytes( "UTF-8" );
        http.setDoOutput( true );
        http.setRequestProperty( "Content-Type", "application/json; charset=utf-8" );
        http.setFixedLengthStreamingMode( bytes.length );
        OutputStream os = http.getOutputStream();
        os.write( bytes );
        os.close();
      }
      int status = http.getResponseCode();
      InputStream is = ( status >= 400 )? http.getErrorStream() : http.getInputStream();
      String text = readText( is );
      if ( status >= 200 && status < 300 ) {
        JSONObject json = parseObject( text );
        if ( json == null ) return new Result( BAD_RESPONSE, status, null, null, null, null, URL_OK );
        return new Result( OK, status, null, null, null, json, URL_OK );
      }
      JSONObject problem = parseObject( text );
      String code   = optText( problem, "code" );
      String title  = optText( problem, "title" );
      String detail = optText( problem, "detail" );
      if ( status == HttpURLConnection.HTTP_UNAUTHORIZED ) {
        return new Result( KEY_REFUSED, status, code, title, detail, null, URL_OK );
      }
      if ( status == HttpURLConnection.HTTP_FORBIDDEN && CODE_KEY_NOT_ACCEPTED.equals( code ) ) {
        return new Result( KEY_NOT_ACCEPTED, status, code, title, detail, null, URL_OK );
      }
      return new Result( PROBLEM, status, code, title, detail, null, URL_OK );
    } catch ( IOException e ) {
      return new Result( NETWORK, 0, null, null, e.getMessage(), null, URL_OK );
    } finally {
      if ( http != null ) http.disconnect();
    }
  }

  // ---------------------------------------------------------------------
  // utilities

  /** @return the text of a stream (UTF-8), empty if the stream is null
   * @param is   input stream
   */
  private static String readText( InputStream is ) throws IOException
  {
    if ( is == null ) return "";
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    try {
      while ( ( n = is.read( buf ) ) > 0 ) bos.write( buf, 0, n );
    } finally {
      is.close();
    }
    return bos.toString( "UTF-8" );
  }

  /** @return the JSON object of a text, or null if the text is not a JSON object
   * @param text   input text
   */
  private static JSONObject parseObject( String text )
  {
    if ( text == null ) return null;
    text = text.trim();
    if ( ! text.startsWith( "{" ) ) return null;
    try {
      return new JSONObject( text );
    } catch ( JSONException e ) {
      return null;
    }
  }

  /** @return a string member of a JSON object, or null if missing
   * @param json   JSON object (can be null)
   * @param name   member name
   */
  private static String optText( JSONObject json, String name )
  {
    if ( json == null || ! json.has( name ) || json.isNull( name ) ) return null;
    return json.optString( name, null );
  }

}
