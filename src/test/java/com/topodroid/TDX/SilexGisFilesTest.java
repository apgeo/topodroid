package com.topodroid.TDX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The files of a SilexGIS upload: names, roles, the out folder and the limits of the server.
 * The files are invented, a few bytes each.
 */
public class SilexGisFilesTest
{
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private File file( File dir, String name, int size ) throws IOException
  {
    File f = new File( dir, name );
    try ( FileOutputStream fos = new FileOutputStream( f ) ) {
      for ( int k = 0; k < size; ++k ) fos.write( 'x' );
    }
    return f;
  }

  private static final List< String > ACCEPTED = Arrays.asList( ".zip", ".th", ".th2", ".thconfig", ".svx", ".csv", ".kml", ".pdf" );

  @Test
  public void theArchiveTheSurveyAndTheSketchesKeepTheirRoles() throws IOException
  {
    File out = tmp.newFolder( "out" );
    SilexGisFiles files = new SilexGisFiles();
    assertTrue( files.add( "pestera_inventata.zip", file( tmp.getRoot(), "pestera_inventata.3.zip", 30 ), SilexGisFiles.ROLE_ARCHIVE ) );
    assertTrue( files.add( "pestera_inventata-1p.th2", file( out, "pestera_inventata-1p.th2", 7 ), SilexGisFiles.ROLE_TH2 ) );
    assertTrue( files.add( "pestera_inventata.th", file( out, "pestera_inventata.th", 5 ), SilexGisFiles.ROLE_TH ) );
    assertEquals( 3, files.size() );
    SilexGisFiles.Item zip = files.items().get( 0 );
    assertEquals( "the archive is declared by the survey name, whatever the local copy is called", "pestera_inventata.zip", zip.name );
    assertEquals( "pestera_inventata.3.zip", zip.file.getName() );
    assertEquals( 30, zip.size );
    assertEquals( SilexGisFiles.ROLE_ARCHIVE, zip.role );
    assertEquals( SilexGisFiles.ROLE_TH2, files.items().get( 1 ).role );
    assertEquals( SilexGisFiles.ROLE_TH, files.items().get( 2 ).role );
    assertEquals( 42, files.totalSize() );
    assertNull( files.check( 1000, 64 ) );
  }

  @Test
  public void aNameIsListedOnceWhateverItsCaseAndThereIsOneArchive() throws IOException
  {
    SilexGisFiles files = new SilexGisFiles();
    File a = file( tmp.getRoot(), "a.zip", 3 );
    File b = file( tmp.getRoot(), "b.zip", 3 );
    assertTrue( files.add( "s.zip", a, SilexGisFiles.ROLE_ARCHIVE ) );
    assertFalse( "a second archive", files.add( "t.zip", b, SilexGisFiles.ROLE_ARCHIVE ) );
    assertFalse( "the same name in another case", files.add( "S.ZIP", b, SilexGisFiles.ROLE_OTHER ) );
    assertFalse( "a file that does not exist", files.add( "s.th", new File( tmp.getRoot(), "none.th" ), SilexGisFiles.ROLE_TH ) );
    assertFalse( "a folder", files.add( "s.th", tmp.getRoot(), SilexGisFiles.ROLE_TH ) );
    assertFalse( "an empty file: the server refuses it", files.add( "s.th", file( tmp.getRoot(), "empty.th", 0 ), SilexGisFiles.ROLE_TH ) );
    assertEquals( 1, files.size() );
  }

  @Test
  public void theOutFolderAddsTheOtherFilesButNoZipAndNothingTwice() throws IOException
  {
    File out = tmp.newFolder( "out" );
    file( out, "s.th", 4 );
    file( out, "s.svx", 4 );
    file( out, "s.pdf", 4 );
    file( out, "old.zip", 4 );
    file( out, "s.tdr", 4 ); // not accepted by the server
    file( out, "s.kml", 0 ); // an empty export
    new File( out, "sub" ).mkdir();
    SilexGisFiles files = new SilexGisFiles();
    files.add( "s.zip", file( tmp.getRoot(), "s.zip", 4 ), SilexGisFiles.ROLE_ARCHIVE );
    files.add( "s.th", new File( out, "s.th" ), SilexGisFiles.ROLE_TH );
    List< String > skipped = files.addFolder( out, ACCEPTED );
    assertEquals( Arrays.asList( "old.zip", "s.tdr" ), skipped );
    assertEquals( 4, files.size() );
    assertEquals( "s.pdf", files.items().get( 2 ).name ); // sorted by name
    assertEquals( SilexGisFiles.ROLE_OTHER, files.items().get( 2 ).role );
    assertEquals( "s.svx", files.items().get( 3 ).name );
    assertEquals( "the survey file keeps its role", SilexGisFiles.ROLE_TH, files.items().get( 1 ).role );
  }

  @Test
  public void otherFilesOfAKindTheServerRefusesAreDroppedButNotTheSurveyFiles() throws IOException
  {
    SilexGisFiles files = new SilexGisFiles();
    files.add( "s.zip", file( tmp.getRoot(), "s.zip", 4 ), SilexGisFiles.ROLE_ARCHIVE );
    files.add( "s.th", file( tmp.getRoot(), "s.th", 4 ), SilexGisFiles.ROLE_TH );
    files.add( "s.gpx", file( tmp.getRoot(), "s.gpx", 4 ), SilexGisFiles.ROLE_OTHER );
    files.add( "s.csv", file( tmp.getRoot(), "s.csv", 4 ), SilexGisFiles.ROLE_OTHER );
    assertEquals( Arrays.asList( "s.gpx" ), files.dropNotAccepted( ACCEPTED ) );
    assertEquals( 3, files.size() );
    assertTrue( files.dropNotAccepted( null ).isEmpty() );
  }

  @Test
  public void theLimitsOfTheServerNameTheFileThatBreaksThem() throws IOException
  {
    SilexGisFiles files = new SilexGisFiles();
    files.add( "s.th", file( tmp.getRoot(), "s.th", 4 ), SilexGisFiles.ROLE_TH );
    assertEquals( SilexGisFiles.NO_ARCHIVE, files.check( 100, 64 ).kind );
    files.add( "s.zip", file( tmp.getRoot(), "s.zip", 50 ), SilexGisFiles.ROLE_ARCHIVE );
    SilexGisFiles.Problem p = files.check( 49, 64 );
    assertEquals( SilexGisFiles.TOO_LARGE, p.kind );
    assertEquals( "s.zip", p.name );
    assertNull( files.check( 50, 64 ) );
    assertEquals( SilexGisFiles.TOO_MANY, files.check( 50, 1 ).kind );
  }

  @Test
  public void namesFromTheServerAreSafeFileNames()
  {
    assertEquals( "s-20261025T013200Z.lox", SilexGisFiles.safeName( "s-20261025T013200Z.lox", "lox" ) );
    assertEquals( "passwd", SilexGisFiles.safeName( "../../etc/passwd", "x" ) );
    assertEquals( "a_b.pdf", SilexGisFiles.safeName( "dir\\a:b.pdf", "x" ) );
    assertEquals( "hidden", SilexGisFiles.safeName( ".hidden", "x" ) );
    assertEquals( "lox", SilexGisFiles.safeName( "..", "lox" ) );
    assertEquals( "lox", SilexGisFiles.safeName( null, "lox" ) );
    assertEquals( ".th2", SilexGisFiles.extension( "s-1P.TH2" ) );
    assertEquals( "", SilexGisFiles.extension( "manifest" ) );
    assertEquals( "", SilexGisFiles.extension( ".profile" ) );
  }
}
