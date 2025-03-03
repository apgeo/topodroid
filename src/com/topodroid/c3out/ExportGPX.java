/** @file ExportGPX.java
 *
 * @author marco corvi
 * @date nov 2011
 *
 * @brief centerline GPX exporter
 * --------------------------------------------------------
 *  Copyright This software is distributed under GPL-3.0 or later
 *  See the file COPYING.
 * --------------------------------------------------------
 */
package com.topodroid.c3out;

import com.topodroid.utils.TDLog;
import com.topodroid.utils.TDUtil;
import com.topodroid.TDX.TglParser;
import com.topodroid.TDX.Triangle3D;
import com.topodroid.TDX.Vector3D;
import com.topodroid.TDX.Cave3DSurvey;
import com.topodroid.TDX.Cave3DStation;
import com.topodroid.TDX.Cave3DFix;
import com.topodroid.TDX.Cave3DShot;
import com.topodroid.prefs.TDSetting;
import com.topodroid.mag.Geodetic;
import com.topodroid.c3walls.cw.CWFacet;
import com.topodroid.c3walls.cw.CWPoint;


import java.util.Locale;
import java.util.List;
import java.util.ArrayList;

import java.io.BufferedWriter;
import java.io.PrintWriter;
// import java.io.PrintStream;
// import java.io.FileOutputStream;
// import java.io.BufferedOutputStream;
// import java.io.FileNotFoundException;
import java.io.IOException;

public class ExportGPX extends ExportGeo
{
  ArrayList<CWFacet> mFacets;
  public ArrayList< Triangle3D > mTriangles;

  public ExportGPX()
  {
    mFacets = new ArrayList< CWFacet >();
    mTriangles = null;
  }

  public void add( CWFacet facet ) { mFacets.add( facet ); }

  public void add( CWPoint v1, CWPoint v2, CWPoint v3 )
  {
     mFacets.add( new CWFacet( v1, v2, v3 ) );
  }

  /** export the model in GPX format
   * @param osw    output buffer writer
   * @param data   model data
   * @param do_splays  whether to include splays
   * @param do_walls   whether to include walls
   * @param do_station whether to include stations
   * @return true on success
   */
  public boolean exportASCII( BufferedWriter osw, TglParser data, boolean do_splays, boolean do_walls, boolean do_station )
  {
    String name = data.getName();
    boolean ret = true;
    if ( data == null ) return false; // always false

    if ( ! getGeolocalizedData( data, 0.0 ) ) { // FIXME declination 0.0
      TDLog.e( "GPX no geolocalized station");
      return false;
    }
    boolean single_track = TDSetting.mGPXSingleTrack;

    // TODO use survey colors
    List< Cave3DSurvey > surveys  = data.getSurveys();

    List< Cave3DStation> stations = data.getStations();
    // List< Cave3DShot>    shots    = data.getShots();
    // List< Cave3DShot>    splays   = data.getSplays();
    // TDLog.v( "GPX export splays " + do_splays + " stations " + do_station + " single track " + single_track + " surveys " + surveys.size() + " stations " + stations.size() );
    double minlat = Double.MAX_VALUE;
    double maxlat = Double.MIN_VALUE;
    double minlon = Double.MAX_VALUE;
    double maxlon = Double.MIN_VALUE;
    for ( Cave3DStation st : stations ) {
      double e = getENC( st );
      double n = getNNC( st );
      if ( e < minlon ) minlon = e;
      if ( e > maxlon ) maxlon = e;
      if ( n < minlat ) minlat = n;
      if ( n > maxlat ) maxlat = n;
    }

    // now write the GPX
    try {
      PrintWriter pw = new PrintWriter( osw );

      pw.format(Locale.US, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      pw.format(Locale.US, "<gpx version=\"1.1\" creator=\"TopoDroid\" xmlns=\"http://www.topografix.com/GPX/1/1\"\n"); 
      pw.format(Locale.US, "    xmlns:osmand=\"https://osmand.net\"\n"); 
      pw.format(Locale.US, "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"); 
      pw.format(Locale.US, "    xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n");
      pw.format(Locale.US, "  <time>%s</time>\n", TDUtil.currentDateTime() );
      pw.format(Locale.US, "  <bounds minlat=\"%.7f\" minlon=\"%.7f\" maxlat=\"%.7f\" maxlon=\"%.7f\"/>\n", minlat, minlon, maxlat, maxlon );
      pw.format(Locale.US, "  <extensions>\n");
      pw.format(Locale.US, "    <color>#ff0000</color>\n"); // red
      pw.format(Locale.US, "    <width>thin</width>\n");
      pw.format(Locale.US, "  </extensions>\n");

      if ( do_station ) { // waypoints are out of tracks
       for ( Cave3DSurvey survey : surveys ) {
          stations = survey.getStations();
          // pw.format(Locale.US, "<Folder>\n");
          // pw.format(Locale.US, "  <name>stations</name>\n" );
          for ( Cave3DStation st : stations ) {
            printWayPoint( pw, st );
          }
          // pw.format(Locale.US, "</Folder>\n");
        }
      }

      if ( ! single_track ) {
        for ( Cave3DSurvey survey : surveys ) {
          String survey_name = survey.getName();
          // int    sid  = survey.getId();
          pw.format(Locale.US, "<trk>\n");
          pw.format(Locale.US, "  <name>%s</name>\n", survey_name );
          // pw.format(Locale.US, "  <extensions>\n"); // ineffective in OsmAnd
          // pw.format(Locale.US, "    <color>#%06x</color>\n", (0x00ffffff & survey.getColor() ) );
          // pw.format(Locale.US, "  </extensions>\n");

          List< Cave3DShot > survey_shots = survey.getShots();
          Cave3DStation last = null;
          for ( Cave3DShot sh : survey_shots ) {
            // if ( sh.mSurveyId != sid ) continue;
            Cave3DStation sf = sh.from_station;
            Cave3DStation st = sh.to_station;
            if ( sf == null || st == null ) continue;
            if ( last == null ) {
              pw.format(Locale.US, "    <trkseg>\n");
              printTrackPoint( pw, sf );
            } else if ( last != sf ) {
              pw.format(Locale.US, "    </trkseg>\n");
              pw.format(Locale.US, "    <trkseg>\n");
              printTrackPoint( pw, sf );
            }
            printTrackPoint( pw, st );
            last = st;
          }
          if ( last != null ) {
            pw.format(Locale.US, "    </trkseg>\n");
          }

          if ( do_splays ) {
            List< Cave3DShot > splays = survey.getSplays();
            for ( Cave3DShot sp : splays ) {
              Cave3DStation sf = sp.from_station;
              if ( sf == null ) continue;
              Vector3D v = sf.sum( sp.toVector3D() );
              pw.format(Locale.US, "    <trkseg>\n");
              printTrackPoint( pw, sf );
              printTrackPoint( pw, v );
              pw.format(Locale.US, "    </trkseg>\n");
            }
          }
          pw.format(Locale.US, "</trk>\n");
        }
      } else { // single track
        // pw.format(Locale.US, "<trk>\n");
        // pw.format(Locale.US, "  <name>%s</name>\n", name );

        pw.format(Locale.US, "<trk>\n");
        pw.format(Locale.US, "  <name>%s</name>\n", name );
        for ( Cave3DSurvey survey : surveys ) {
          List< Cave3DShot > survey_shots = survey.getShots();
          Cave3DStation last = null;
          for ( Cave3DShot sh : survey_shots ) {
            // if ( sh.mSurveyId != sid ) continue;
            Cave3DStation sf = sh.from_station;
            Cave3DStation st = sh.to_station;
            if ( sf == null || st == null ) continue;
            if ( last == null ) {
              pw.format(Locale.US, "    <trkseg>\n");
              printTrackPoint( pw, sf );
            } else if ( last != sf ) {
              pw.format(Locale.US, "    </trkseg>\n");
              pw.format(Locale.US, "    <trkseg>\n");
              printTrackPoint( pw, sf );
            }
            printTrackPoint( pw, st );
            last = st;
          }
          if ( last != null ) {
            pw.format(Locale.US, "    </trkseg>\n");
          }
        }

        if ( do_splays ) {
          for ( Cave3DSurvey survey : surveys ) {
            List< Cave3DShot > splays = survey.getSplays();
            for ( Cave3DShot sp : splays ) {
              Cave3DStation sf = sp.from_station;
              if ( sf == null ) continue;
              Vector3D v = sf.sum( sp.toVector3D() );
              pw.format(Locale.US, "    <trkseg>\n");
              printTrackPoint( pw, sf );
              printTrackPoint( pw, v );
              pw.format(Locale.US, "    </trkseg>\n");
            }
          }
        }
        pw.format(Locale.US, "</trk>\n");
      }
      pw.format(Locale.US, "</gpx>\n");
      osw.flush();
      osw.close();
      return true;
    } catch ( IOException e ) {
      TDLog.e( "GPX IO error " + e.getMessage() );
      return false;
    }
  }

  private void printWayPoint( PrintWriter pw, Cave3DStation st )
  {
    double e = getENC( st );
    double n = getNNC( st );
    double z = getZ( st );
    pw.format(Locale.US, "  <wpt lon=\"%.7f\" lat=\"%.7f\">\n", e, n );
    pw.format(Locale.US, "    <ele>%.2f</ele>\n", z );
    pw.format(Locale.US, "    <name><![CDATA[%s]]></name>\n", st.getFullName() );
    pw.format(Locale.US, "    <desc></desc>\n");
    pw.format(Locale.US, "    <sym>Dot</sym>\n");
    // pw.format(Locale.US, "    <type></type>\n");
    // pw.format(Locale.US, "    <time></time>\n");
    pw.format(Locale.US, "  </wpt>\n");
  }

  private void printTrackPoint( PrintWriter pw, Vector3D sf )
  {
    double ef = getENC( sf );
    double nf = getNNC( sf );
    double zf = getZ( sf );
    pw.format(Locale.US, "      <trkpt lon=\"%.7f\" lat=\"%.7f\"><ele>%.2f</ele></trkpt>\n", ef, nf, zf ); 
  }
}

