package btools.mapcreator;

import java.io.*;
import java.util.*;

import btools.util.*;

import btools.expressions.BExpressionContext;

/**
 * WayLinker finally puts the pieces together
 * to create the rd5 files. For each 5*5 tile,
 * the corresponding nodefile and wayfile is read,
 * plus the (global) bordernodes file, and an rd5
 * is written
 *
 * @author ab
 */
public class WayLinker extends MapCreatorBase
{
  private File nodeTilesIn;
  private File dataTilesOut;
  private File borderFileIn;

  private String dataTilesSuffix;

  private boolean readingBorder;

  private CompactLongMap<OsmNodeP> nodesMap;
  private List<OsmNodeP> nodesList;
  private CompactLongSet borderSet;
  private short lookupVersion;

  private BExpressionContext expctxWay;

  private int minLon;
  private int minLat;
  
  private void reset()
  {
    minLon = -1;
    minLat = -1;
    nodesMap = new CompactLongMap<OsmNodeP>();
    borderSet = new CompactLongSet();
  }
  
  public static void main(String[] args) throws Exception
  {
    System.out.println("*** WayLinker: Format a regionof an OSM map for routing");
    if (args.length != 7)
    {
      System.out.println("usage: java WayLinker <node-tiles-in> <way-tiles-in> <bordernodes> <lookup-file> <profile-file> <data-tiles-out> <data-tiles-suffix> ");
      return;
    }
    new WayLinker().process( new File( args[0] ), new File( args[1] ), new File( args[2] ), new File( args[3] ), new File( args[4] ), new File( args[5] ), args[6] );
  }

  public void process( File nodeTilesIn, File wayTilesIn, File borderFileIn, File lookupFile, File profileFile, File dataTilesOut, String dataTilesSuffix ) throws Exception
  {
    this.nodeTilesIn = nodeTilesIn;
    this.dataTilesOut = dataTilesOut;
    this.borderFileIn = borderFileIn;
    this.dataTilesSuffix = dataTilesSuffix;
    
    // read lookup file to get the lookup-version
    expctxWay = new BExpressionContext("way");
    expctxWay.readMetaData( lookupFile );
    lookupVersion = expctxWay.lookupVersion;
    expctxWay.parseFile( profileFile, "global" );

    // then process all segments    
    new WayIterator( this, true ).processDir( wayTilesIn, ".wt5" );
  }

  @Override
  public void wayFileStart( File wayfile ) throws Exception
  {
    // process corresponding node-file, if any
    File nodeFile = fileFromTemplate( wayfile, nodeTilesIn, "u5d" );
    if ( nodeFile.exists() )
    {
      reset();

      // read the border file
      readingBorder = true;
      new NodeIterator( this, false ).processFile( borderFileIn );
      borderSet = new FrozenLongSet( borderSet );

      // read this tile's nodes
      readingBorder = false;
      new NodeIterator( this, true ).processFile( nodeFile );
    
      // freeze the nodes-map
      FrozenLongMap<OsmNodeP> nodesMapFrozen = new FrozenLongMap<OsmNodeP>( nodesMap );
      nodesMap = nodesMapFrozen;
      nodesList = nodesMapFrozen.getValueList();
    }
  }

  @Override
  public void nextNode( NodeData data ) throws Exception
  {
    OsmNodeP n = data.description == 0L ? new OsmNodeP() : new OsmNodePT(data.description);
    n.ilon = data.ilon;
    n.ilat = data.ilat;
    n.selev = data.selev;
    n.isBorder = readingBorder;
    if ( readingBorder || (!borderSet.contains( data.nid )) )
    {
      nodesMap.fastPut( data.nid, n );
    }

    if ( readingBorder )
    {
      borderSet.fastAdd( data.nid );
      return;
    }

    // remember the segment coords
    int min_lon = (n.ilon / 5000000 ) * 5000000;
    int min_lat = (n.ilat / 5000000 ) * 5000000;
    if ( minLon == -1 ) minLon = min_lon;
    if ( minLat == -1 ) minLat = min_lat;
    if ( minLat != min_lat || minLon != min_lon )
      throw new IllegalArgumentException( "inconsistent node: " + n.ilon + " " + n.ilat );
  }

  @Override
  public void nextWay( WayData way ) throws Exception
  {
    long description = way.description;
    long reverseDescription = description | 1L; // (add reverse bit)

    // filter according to profile
    expctxWay.evaluate( description, null );
    boolean ok = expctxWay.getCostfactor() < 10000.; 
    expctxWay.evaluate( reverseDescription, null );
    ok |= expctxWay.getCostfactor() < 10000.;
    
    if ( !ok ) return;
    
    byte lowbyte = (byte)description;

    OsmNodeP n1 = null;
    OsmNodeP n2 = null;
    for (int i=0; i<way.nodes.size(); i++)
    {
      long nid = way.nodes.get(i);
      n1 = n2;
      n2 = nodesMap.get( nid );
      if ( n1 != null && n2 != null )
      {
        OsmLinkP l1 = new OsmLinkP();
        l1.targetNode = n2;
        l1.descriptionBitmap = description;
        n1.addLink( l1 );

        OsmLinkP l2 = new OsmLinkP();
        l2.targetNode = n1;
        l2.descriptionBitmap = reverseDescription;
        
        n2.addLink( l2 );
      }
      if ( n2 != null )
      {
        n2.wayAndBits &= lowbyte;
        if ( n2 instanceof OsmNodePT ) ((OsmNodePT)n2).wayOrBits |= lowbyte;
      }
    }
  }

  @Override
  public void wayFileEnd( File wayfile ) throws Exception
  {
    nodesMap = null;
    borderSet = null;

    int maxLon = minLon + 5000000;    
    int maxLat = minLat + 5000000;

    // write segment data to individual files
    {
      int nLonSegs = (maxLon - minLon)/1000000;
      int nLatSegs = (maxLat - minLat)/1000000;
      
      // sort the nodes into segments
      LazyArrayOfLists<OsmNodeP> seglists = new LazyArrayOfLists<OsmNodeP>(nLonSegs*nLatSegs);
      for( OsmNodeP n : nodesList )
      {
        if ( n == null || n.firstlink == null || n.isTransferNode() ) continue;
        if ( n.ilon < minLon || n.ilon >= maxLon
          || n.ilat < minLat || n.ilat >= maxLat ) continue;
        int lonIdx = (n.ilon-minLon)/1000000;
        int latIdx = (n.ilat-minLat)/1000000;
        
        int tileIndex = lonIdx * nLatSegs + latIdx;
        seglists.getList(tileIndex).add( n );
      }
      nodesList = null;
      seglists.trimAll();

      // open the output file
      File outfile = fileFromTemplate( wayfile, dataTilesOut, dataTilesSuffix );
      DataOutputStream os = createOutStream( outfile );

      // write 5*5 index dummy
      long[] fileIndex = new long[25];
      for( int i55=0; i55<25; i55++)
      {
        os.writeLong( 0 );
      }
      long filepos = 200L;
      
      // sort further in 1/80-degree squares
      for( int lonIdx = 0; lonIdx < nLonSegs; lonIdx++ )
      {
        for( int latIdx = 0; latIdx < nLatSegs; latIdx++ )
        {
          int tileIndex = lonIdx * nLatSegs + latIdx;
          if ( seglists.getSize(tileIndex) > 0 )
          {
            List<OsmNodeP> nlist = seglists.getList(tileIndex);
            
            LazyArrayOfLists<OsmNodeP> subs = new LazyArrayOfLists<OsmNodeP>(6400);
            byte[][] subByteArrays = new byte[6400][];
            for( int ni=0; ni<nlist.size(); ni++ )
            {
              OsmNodeP n = nlist.get(ni);
              int subLonIdx = (n.ilon - minLon) / 12500 - 80*lonIdx;
              int subLatIdx = (n.ilat - minLat) / 12500 - 80*latIdx;
              int si = subLatIdx*80 + subLonIdx;
              subs.getList(si).add( n );
            }
            subs.trimAll();
            int[] posIdx = new int[6400];
            int pos = 25600;
            for( int si=0; si<6400; si++)
            {
              List<OsmNodeP> subList = subs.getList(si);
              if ( subList.size() > 0 )
              {
                Collections.sort( subList );

                ByteArrayOutputStream bos = new ByteArrayOutputStream( );
                DataOutputStream dos = new DataOutputStream( bos );
                dos.writeInt( subList.size() );
                for( int ni=0; ni<subList.size(); ni++ )
                {
                  OsmNodeP n = subList.get(ni);
                  n.writeNodeData( dos );
                }
                dos.close();
                byte[] subBytes = bos.toByteArray();
                pos += subBytes.length;
                subByteArrays[si] = subBytes;
              }
              posIdx[si] = pos;
            }

            for( int si=0; si<6400; si++)
            {
              os.writeInt( posIdx[si] );
            }
            for( int si=0; si<6400; si++)
            {
              if ( subByteArrays[si] != null )
              {
                os.write( subByteArrays[si] );
              }
            }
            filepos += pos;
          }
          fileIndex[ tileIndex ] = filepos;
        }
      }
      os.close();
      
      // re-open random-access to write file-index
      RandomAccessFile ra = new RandomAccessFile( outfile, "rw" );
      long versionPrefix = lookupVersion;
      versionPrefix <<= 48;
      for( int i55=0; i55<25; i55++)
      {
        ra.writeLong( fileIndex[i55] | versionPrefix );
      }
      ra.close();
    }
  }
}
