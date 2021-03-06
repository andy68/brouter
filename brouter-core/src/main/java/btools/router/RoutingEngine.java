package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import btools.expressions.BExpressionContext;
import btools.mapaccess.NodesCache;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmLinkHolder;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmNodesMap;

public class RoutingEngine extends Thread
{
  private OsmNodesMap nodesMap;
  private NodesCache nodesCache;
  private OpenSet openSet = new OpenSet();
  private boolean finished = false;

  private List<OsmNodeNamed> waypoints = null;
  private int linksProcessed = 0;

  private OsmTrack foundTrack = new OsmTrack();
  private OsmTrack foundRawTrack = null;
  private int alternativeIndex = 0;

  private String errorMessage = null;

  private volatile boolean terminated;

  private String segmentDir;
  private String outfileBase;
  private String logfileBase;
  private boolean infoLogEnabled;
  private RoutingContext routingContext;

  private double airDistanceCostFactor;
  private OsmTrack guideTrack;

  private OsmPathElement matchPath;
  
  private long startTime;
  private long maxRunningTime;
  
  public boolean quite = false;

  public RoutingEngine( String outfileBase, String logfileBase, String segmentDir,
          List<OsmNodeNamed> waypoints, RoutingContext rc )
  {
    this.segmentDir = segmentDir;
    this.outfileBase = outfileBase;
    this.logfileBase = logfileBase;
    this.waypoints = waypoints;
    this.infoLogEnabled = outfileBase != null;
    this.routingContext = rc;

    if ( rc.localFunction != null )
    {
      String profileBaseDir = System.getProperty( "profileBaseDir" );
      File profileDir;
      File profileFile;
      if ( profileBaseDir == null )
      {
        profileDir = new File( rc.localFunction ).getParentFile();
        profileFile = new File( rc.localFunction ) ;
      }
      else
      {
        profileDir = new File( profileBaseDir );
        profileFile = new File( profileDir, rc.localFunction + ".brf" ) ;
      }
      BExpressionContext expctxGlobal = new BExpressionContext( "global" );
      expctxGlobal.readMetaData( new File( profileDir, "lookups.dat" ) );
      expctxGlobal.parseFile( profileFile, null );
      expctxGlobal.evaluate( 1L, rc.messageHandler );
      rc.readGlobalConfig(expctxGlobal);

      rc.expctxWay = new BExpressionContext( "way", 4096 );
      rc.expctxWay.readMetaData( new File( profileDir, "lookups.dat" ) );
      rc.expctxWay.parseFile( profileFile, "global" );

      rc.expctxNode = new BExpressionContext( "node", 1024 );
      rc.expctxNode.readMetaData( new File( profileDir, "lookups.dat" ) );
      rc.expctxNode.parseFile( profileFile, "global" );
    }
  }

  private void logInfo( String s )
  {
    if ( infoLogEnabled )
    {
      System.out.println( s );
    }
  }

  public void run()
  {
    doRun( 0 );
  }

  public void doRun( long maxRunningTime )
  {
    try
    {
      startTime = System.currentTimeMillis();
      this.maxRunningTime = maxRunningTime;
      OsmTrack sum = null;
      OsmTrack track = null;
      ArrayList<String> messageList = new ArrayList<String>();
      for( int i=0; !terminated; i++ )
      {
        track = findTrack( sum );
        track.message = "track-length = " + track.distance + " filtered ascend = " + track.ascend
        + " plain-ascend = " +  track.plainAscend + " cost=" + track.cost;
        track.name = "brouter_" + routingContext.getProfileName() + "_" + i;

        messageList.add( track.message );
        track.messageList = messageList;
        if ( outfileBase != null )
        {
          String filename = outfileBase + i + ".gpx";
          OsmTrack oldTrack = new OsmTrack();
          oldTrack.readGpx(filename);
          if ( track.equalsTrack( oldTrack ) )
          {
            if ( sum == null ) sum = new OsmTrack();
            sum.addNodes( track );
            continue;
          }
          track.writeGpx( filename );
          foundTrack = track;
          alternativeIndex = i;
        }
        else
        {
          if ( i == routingContext.getAlternativeIdx() )
          {
            if ( "CSV".equals( System.getProperty( "reportFormat" ) ) )
            {
              track.dumpMessages( null, routingContext );
            }
            else
            {
              if ( !quite )
              {
                System.out.println( track.formatAsGpx() );
              }
            }
            foundTrack = track;
          }
          else
          {
            if ( sum == null ) sum = new OsmTrack();
            sum.addNodes( track );
            continue;
          }
        }
        if ( logfileBase != null )
        {
          String logfilename = logfileBase + i + ".csv";
          track.dumpMessages( logfilename, routingContext );
        }
        break;
      }
      long endTime = System.currentTimeMillis();
      logInfo( "execution time = " + (endTime-startTime)/1000. + " seconds" );
    }
    catch( Exception e)
    {
      errorMessage = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();
      logInfo( "Exception (linksProcessed=" + linksProcessed + ": " + errorMessage );
      e.printStackTrace();
    }
    catch( Error e)
    {
      String hint = cleanOnOOM();
      errorMessage = e.toString() + hint;
      logInfo( "Error (linksProcessed=" + linksProcessed + ": " + errorMessage );
      e.printStackTrace();
    }
    finally
    {
      openSet.clear();
      finished = true; // this signals termination to outside
    }
  }

  public String cleanOnOOM()
  {
	  boolean oom_carsubset_hint = nodesCache == null ? false : nodesCache.oom_carsubset_hint;
      nodesMap = null;
      nodesCache = null;
      terminate();
      return oom_carsubset_hint ? "\nPlease use 'carsubset' maps for long-distance car-routing" : "";
  }      
  
  

  private OsmTrack findTrack( OsmTrack refTrack )
  {
    OsmTrack totaltrack = new OsmTrack();
    MatchedWaypoint[] wayointIds = new MatchedWaypoint[waypoints.size()];

    // check for a track for that target
    OsmTrack nearbyTrack = null;
    if ( refTrack == null )
    {
      nearbyTrack = OsmTrack.readBinary( routingContext.rawTrackPath, waypoints.get( waypoints.size()-1) );
      if ( nearbyTrack != null )
      {
          wayointIds[waypoints.size()-1] = nearbyTrack.endPoint;
      }
    }
    
    // match waypoints to nodes
    for( int i=0; i<waypoints.size(); i++ )
    {
      if ( wayointIds[i] == null )
      {
        wayointIds[i] = matchNodeForPosition( waypoints.get(i) );
      }
    }

    for( int i=0; i<waypoints.size() -1; i++ )
    {
      OsmTrack seg = searchTrack( wayointIds[i], wayointIds[i+1], i == waypoints.size()-2 ? nearbyTrack : null, refTrack );
      if ( seg == null ) return null;
      totaltrack.appendTrack( seg );
    }
    return totaltrack;
  }

  // geometric position matching finding the nearest routable way-section
  private MatchedWaypoint matchNodeForPosition( OsmNodeNamed wp )
  {
     try
     {
         routingContext.setWaypoint( wp, false );
         
         int minRingWith = 1;
         for(;;)
         {
           MatchedWaypoint mwp = _matchNodeForPosition( wp, minRingWith );
           if ( mwp.node1 != null )
           {
             int mismatch = wp.calcDistance( mwp.crosspoint );
             if ( mismatch < 50*minRingWith )
             {
               return mwp;
             }
           }
           if ( minRingWith++ == 5 )
           {
             throw new IllegalArgumentException( wp.name + "-position not mapped" );
           }
         }
     }
     finally
     {
         routingContext.unsetWaypoint();
     }
  }

  private MatchedWaypoint _matchNodeForPosition( OsmNodeNamed wp, int minRingWidth )
  {
    wp.radius = 1e9;
    resetCache();
    preloadPosition( wp, minRingWidth, 2000 );
    nodesCache.distanceChecker = routingContext;
    List<OsmNode> nodeList = nodesCache.getAllNodes();

    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.waypoint = wp;

    // first loop just to expand reverse links
    for( OsmNode n : nodeList )
    {
        if ( !nodesCache.obtainNonHollowNode( n ) )
        {
          continue;
        }
        expandHollowLinkTargets( n, false );
        OsmLink startLink = new OsmLink();
        startLink.targetNode = n;
        OsmPath startPath = new OsmPath( startLink );
        startLink.addLinkHolder( startPath );
        for( OsmLink link = n.firstlink; link != null; link = link.next )
        {
          if ( link.counterLinkWritten ) continue; // reverse link not found
          OsmNode nextNode = link.targetNode;
          if ( nextNode.isHollow() ) continue; // border node?
          if ( nextNode.firstlink == null ) continue; // don't care about dead ends
          if ( nextNode == n ) continue; // ?
          double oldRadius = wp.radius;
          OsmPath testPath = new OsmPath( n, startPath, link, null, false, routingContext );
          if ( wp.radius < oldRadius )
          {
           if ( testPath.cost < 0 )
           {
             wp.radius = oldRadius; // no valid way
           }
           else
           {
             mwp.node1 = n;
             mwp.node2 = nextNode;
             mwp.radius = wp.radius;
             mwp.cost = testPath.cost;
             mwp.crosspoint = new OsmNodeNamed();
             mwp.crosspoint.ilon = routingContext.ilonshortest;
             mwp.crosspoint.ilat = routingContext.ilatshortest;
           }
          }
        }
    }
    return mwp;
  }

  // expand hollow link targets and resolve reverse links
  private void expandHollowLinkTargets( OsmNode n, boolean failOnReverseNotFound )
  {
    for( OsmLink link = n.firstlink; link != null; link = link.next )
    {
      if ( ! nodesCache.obtainNonHollowNode( link.targetNode ) )
      {
        continue;
      }

      if ( link.counterLinkWritten )
      {
        OsmLink rlink = link.targetNode.getReverseLink( n.getILon(), n.getILat() );
        if ( rlink == null )
        {
          if ( failOnReverseNotFound ) throw new RuntimeException( "reverse link not found!" );
        }
        else
        {
          link.descriptionBitmap = rlink.descriptionBitmap;
          link.firsttransferBytes = rlink.firsttransferBytes;
          link.counterLinkWritten = false;
        }
      }
    }
    n.wasProcessed = true;
  }

  private OsmTrack searchTrack( MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack nearbyTrack, OsmTrack refTrack )
  {
    OsmTrack track = null;
    double[] airDistanceCostFactors = new double[]{ routingContext.pass1coefficient, routingContext.pass2coefficient };
    boolean isDirty = false;
    
    if ( nearbyTrack != null )
    {
      airDistanceCostFactor = 0.;
      try
      {
        track = findTrack( "re-routing", startWp, endWp, nearbyTrack , refTrack, true );
      }
      catch( IllegalArgumentException iae )
      {
        // fast partial recalcs: if that timed out, but we had a match,
        // build the concatenation from the partial and the nearby track
        if ( matchPath != null )
        {
          track = mergeTrack( matchPath, nearbyTrack );
          isDirty = true;
        }
    	maxRunningTime += System.currentTimeMillis() - startTime; // reset timeout...
      }
    }

    if ( track == null )
    {
      for( int cfi = 0; cfi < airDistanceCostFactors.length && !terminated; cfi++ )
      {
        airDistanceCostFactor = airDistanceCostFactors[cfi];
        
        if ( airDistanceCostFactor < 0. )
        {
          continue;
        }
      
        OsmTrack t = findTrack( cfi == 0 ? "pass0" : "pass1", startWp, endWp, track , refTrack, false  );
        if ( t == null && track != null && matchPath != null )
        {
          // ups, didn't find it, use a merge
          t = mergeTrack( matchPath, track );
        }
        if ( t != null )
        {
          track = t;
        }
        else
        {
          throw new IllegalArgumentException( "no track found at pass=" + cfi );
        }
      }
    }
    if ( track == null ) throw new IllegalArgumentException( "no track found" );
    
    if ( refTrack == null && !isDirty )
    {
      track.endPoint = endWp;
      foundRawTrack = track;
    }

    // final run for verbose log info and detail nodes
    airDistanceCostFactor = 0.;
    guideTrack = track;
    try
    {
      OsmTrack tt = findTrack( "re-tracking", startWp, endWp, null , refTrack, false );
      if ( tt == null ) throw new IllegalArgumentException( "error re-tracking track" );
      return tt;
    }
    finally
    {
      guideTrack = null;
    }
  }


  private void resetCache()
  {
    nodesMap = new OsmNodesMap();
    nodesCache = new NodesCache(segmentDir, nodesMap, routingContext.expctxWay.lookupVersion, routingContext.carMode, nodesCache );
  }

  private OsmNode getStartNode( long startId )
  {
    // initialize the start-node
    OsmNode start = nodesMap.get( startId );
    if ( start == null )
    {
      start = new OsmNode( startId );
      start.setHollow();
      nodesMap.put( startId, start );
    }
    if ( !nodesCache.obtainNonHollowNode( start ) )
    {
      return null;
    }
    expandHollowLinkTargets( start, true );
    return start;
  }

  private OsmPath getStartPath( OsmNode n1, OsmNode n2, MatchedWaypoint mwp, MatchedWaypoint endWp, boolean sameSegmentSearch )
  {
    OsmPath p = getStartPath( n1, n2, mwp.waypoint, endWp.crosspoint );
    
    // special case: start+end on same segment
    if ( sameSegmentSearch )
    {
      OsmPath pe = getEndPath( n1, p.getLink(), endWp.crosspoint, endWp.crosspoint );
      OsmPath pt = getEndPath( n1, p.getLink(), null, endWp.crosspoint );
      int costdelta = pt.cost - p.cost;
      if ( pe.cost >= costdelta )
      {
    	pe.cost -= costdelta;
    	pe.adjustedCost -= costdelta;

    	if ( guideTrack != null )
    	{
    	  // nasty stuff: combine the path cause "new OsmPath()" cannot handle start+endpoint
    	  OsmPathElement startElement = p.originElement;
          while( startElement.origin != null )
          {
            startElement = startElement.origin;
          }
    	  if ( pe.originElement.cost > costdelta )
    	  {
    	    OsmPathElement e = pe.originElement;
       	    while( e.origin != null && e.origin.cost > costdelta )
    	    {
    	      e = e.origin;
    	      e.cost -= costdelta;
    	    }
    	    e.origin = startElement;
    	  }
    	  else
    	  {
    	    pe.originElement = startElement;
    	  }
    	}
        return pe;
      }
    }
    return p;
  }

    
    
  private OsmPath getStartPath( OsmNode n1, OsmNode n2, OsmNodeNamed wp, OsmNode endPos )
  {
    try
    {
      routingContext.setWaypoint( wp, false );
      OsmPath bestPath = null;
      OsmLink bestLink = null;
      OsmLink startLink = new OsmLink();
      startLink.targetNode = n1;
      OsmPath startPath = new OsmPath( startLink );
      startLink.addLinkHolder( startPath );
      double minradius = 1e10;
      for( OsmLink link = n1.firstlink; link != null; link = link.next )
      {
        OsmNode nextNode = link.targetNode;
        if ( nextNode.isHollow() ) continue; // border node?
        if ( nextNode.firstlink == null ) continue; // don't care about dead ends
        if ( nextNode == n1 ) continue; // ?
        if ( nextNode != n2 ) continue; // just that link

         wp.radius = 1e9;
         OsmPath testPath = new OsmPath( null, startPath, link, null, guideTrack != null, routingContext );
         testPath.setAirDistanceCostAdjustment( (int)( nextNode.calcDistance( endPos ) * airDistanceCostFactor ) );
         if ( wp.radius < minradius )
         {
           bestPath = testPath;
           minradius = wp.radius;
           bestLink = link;
         }
      }
      if ( bestLink != null )
      {
        bestLink.addLinkHolder( bestPath );
      }
      bestPath.treedepth = 1;

      return bestPath;
    }
    finally
    {
      routingContext.unsetWaypoint();
    }
  }

  private OsmPath getEndPath( OsmNode n1, OsmLink link, OsmNodeNamed wp, OsmNode endPos )
  {
    try
    {
      if ( wp != null ) routingContext.setWaypoint( wp, true );
      OsmLink startLink = new OsmLink();
      startLink.targetNode = n1;
      OsmPath startPath = new OsmPath( startLink );
      startLink.addLinkHolder( startPath );

      if ( wp != null ) wp.radius = 1e-5;
     
      OsmPath testPath = new OsmPath( n1, startPath, link, null, guideTrack != null, routingContext );
      testPath.setAirDistanceCostAdjustment( 0 );

      return testPath;
    }
    finally
    {
      if ( wp != null ) routingContext.unsetWaypoint();
    }
  }

  private OsmTrack findTrack( String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack costCuttingTrack, OsmTrack refTrack, boolean reducedTimeoutWhenUnmatched )
  {
    boolean verbose = guideTrack != null;

    int maxTotalCost = 1000000000;
    
    logInfo( "findtrack with maxTotalCost=" + maxTotalCost + " airDistanceCostFactor=" + airDistanceCostFactor );

    matchPath = null;
    int nodesVisited = 0;

    resetCache();
    long endNodeId1 = endWp.node1.getIdFromPos();
    long endNodeId2 = endWp.node2.getIdFromPos();
    long startNodeId1 = startWp.node1.getIdFromPos();
    long startNodeId2 = startWp.node2.getIdFromPos();

    OsmNode endPos = endWp.crosspoint;
    
    boolean sameSegmentSearch = ( startNodeId1 == endNodeId1 && startNodeId2 == endNodeId2 )
                             || ( startNodeId1 == endNodeId2 && startNodeId2 == endNodeId1 );
    
    OsmNode start1 = getStartNode( startNodeId1 );
    OsmNode start2 = getStartNode( startNodeId2 );
    if ( start1 == null || start2 == null ) return null;

    OsmPath startPath1 = getStartPath( start1, start2, startWp, endWp, sameSegmentSearch );
    OsmPath startPath2 = getStartPath( start2, start1, startWp, endWp, sameSegmentSearch );

    int maxAdjCostFromQueue = 0;

    synchronized( openSet )
    {
      openSet.clear();
      if ( startPath1.cost >= 0 ) openSet.add( startPath1 );
      if ( startPath2.cost >= 0 ) openSet.add( startPath2 );
    }
    while(!terminated)
    {
      if ( maxRunningTime > 0 )
      {
        long timeout = ( matchPath == null && reducedTimeoutWhenUnmatched ) ? maxRunningTime/3 : maxRunningTime;
        if ( System.currentTimeMillis() - startTime > timeout )
        {
          throw new IllegalArgumentException( operationName + " timeout after " + (timeout/1000) + " seconds" );
        }
      }
      OsmPath path = null;
      synchronized( openSet )
      {
        if ( openSet.size() == 0 ) break;
        path = openSet.first();
        openSet.remove( path );
      }

      if ( path.adjustedCost < maxAdjCostFromQueue && airDistanceCostFactor == 0.)
      {
        throw new RuntimeException( "assertion failed: path.adjustedCost < maxAdjCostFromQueue: " + path.adjustedCost + "<" + maxAdjCostFromQueue );
      }
      maxAdjCostFromQueue = path.adjustedCost;

      nodesVisited++;
      linksProcessed++;
      
      OsmLink currentLink = path.getLink();
      OsmNode currentNode = currentLink.targetNode;
      OsmNode sourceNode = path.getSourceNode();

      long currentNodeId = currentNode.getIdFromPos();
      if ( sourceNode != null )
      {
        long sourceNodeId = sourceNode.getIdFromPos();
        if ( ( sourceNodeId == endNodeId1 && currentNodeId == endNodeId2 )
          || ( sourceNodeId == endNodeId2 && currentNodeId == endNodeId1 ) )
        {
          // track found, compile
          logInfo( "found track at cost " + path.cost +  " nodesVisited = " + nodesVisited );
          return compileTrack( path, verbose );
        }
      }

      // recheck cutoff before doing expensive stuff
      int airDistance2 = currentNode.calcDistance( endPos );
      if ( path.cost + airDistance2 > maxTotalCost + 10 )
      {
        continue;
      }

      if ( !currentNode.wasProcessed )
      {
        expandHollowLinkTargets( currentNode, true );
        nodesMap.removeCompletedNodes();
      }

      if ( sourceNode != null )
      {
        sourceNode.unlinkLink ( currentLink );
      }

      OsmLink counterLink = null;
      for( OsmLink link = currentNode.firstlink; link != null; link = link.next )
      {
        OsmNode nextNode = link.targetNode;

        if ( nextNode.isHollow() )
        {
          continue; // border node?
        }
        if ( nextNode.firstlink == null )
        {
          continue; // don't care about dead ends
        }
        if ( nextNode == sourceNode )
        {
          counterLink = link;
          continue; // border node?
        }

        if ( guideTrack != null )
        {
          int gidx = path.treedepth + 1;
          if ( gidx >= guideTrack.nodes.size() )
          {
            continue;
          }
          OsmPathElement guideNode = guideTrack.nodes.get( gidx );
          if ( nextNode.getILat() != guideNode.getILat() || nextNode.getILon() != guideNode.getILon() )
          {
            continue;
          }
        }

        OsmPath bestPath = null;

        boolean isFinalLink = false;
        long targetNodeId = link.targetNode.getIdFromPos();
        if ( currentNodeId == endNodeId1 || currentNodeId == endNodeId2 )
        {
          if ( targetNodeId == endNodeId1 || targetNodeId == endNodeId2 )
          {
            isFinalLink = true;
          }
        }

        for( OsmLinkHolder linkHolder = currentLink.firstlinkholder; linkHolder != null; linkHolder = linkHolder.getNextForLink() )
        {
          OsmPath otherPath = (OsmPath)linkHolder;
          try
          {
            if ( isFinalLink )
            {
              endWp.crosspoint.radius = 1e-5;
              routingContext.setWaypoint( endWp.crosspoint, true );
            }
            OsmPath testPath = new OsmPath( currentNode, otherPath, link, refTrack, guideTrack != null, routingContext );
            if ( testPath.cost >= 0 && ( bestPath == null || testPath.cost < bestPath.cost ) )
            {
              bestPath = testPath;
            }
          }
          finally
          {
            routingContext.unsetWaypoint();
          }
          if ( otherPath != path )
          {
            synchronized( openSet )
            {
                openSet.remove( otherPath );
            }
          }
        }
        if ( bestPath != null )
        {
          int airDistance = isFinalLink ? 0 : nextNode.calcDistance( endPos );
          bestPath.setAirDistanceCostAdjustment( (int)( airDistance * airDistanceCostFactor ) );
          
          // check for a match with the cost-cutting-track
          if ( costCuttingTrack != null )
          {
            OsmPathElement pe = costCuttingTrack.getLink( currentNodeId, targetNodeId );
            if ( pe != null )
            {
              int costEstimate = bestPath.cost
                               + bestPath.elevationCorrection( routingContext )
                               + ( costCuttingTrack.cost - pe.cost );
              if ( costEstimate <= maxTotalCost )
              {
                matchPath = new OsmPathElement( bestPath );
              }
              if ( costEstimate < maxTotalCost )
              {
                logInfo( "maxcost " + maxTotalCost + " -> " + costEstimate + " airDistance=" + airDistance );
                maxTotalCost = costEstimate;
              }
            }
          }

          if ( isFinalLink || bestPath.cost + airDistance <= maxTotalCost + 10 )
          {
            // add only if this may beat an existing path for that link
        	OsmLinkHolder dominator = link.firstlinkholder;
        	while( dominator != null )
            {
              if ( bestPath.definitlyWorseThan( (OsmPath)dominator, routingContext ) )
              {
                break;
              }
              dominator = dominator.getNextForLink();
            }

        	if ( dominator == null )
        	{
              bestPath.treedepth = path.treedepth + 1;
              link.addLinkHolder( bestPath );
              synchronized( openSet )
              {
                openSet.add( bestPath );
              }
        	}
          }
        }
      }
      // if the counterlink does not yet have a path, remove it
      if ( counterLink != null && counterLink.firstlinkholder == null )
      {
        currentNode.unlinkLink(counterLink);
      }

    }
    return null;
  }

  private void preloadPosition( OsmNode n, int minRingWidth, int minCount )
  {
    int c = 0;
    int ring = 0;
    while( ring <= minRingWidth || ( c < minCount && ring <= 5 ) )
    {
      c += preloadRing( n, ring++ );
    }
  }

  private int preloadRing( OsmNode n, int ring )
  {
    int d = 12500;
    int c = 0;
    for( int idxLat=-ring; idxLat<=ring; idxLat++ )
      for( int idxLon=-ring; idxLon<=ring; idxLon++ )
      {
        int absLat = idxLat < 0 ? -idxLat : idxLat;
        int absLon = idxLon < 0 ? -idxLon : idxLon;
        int max = absLat > absLon ? absLat : absLon;
        if ( max < ring ) continue;
        c += nodesCache.loadSegmentFor( n.ilon + d*idxLon , n.ilat +d*idxLat );
      }
    return c;
  }

  private OsmTrack compileTrack( OsmPath path, boolean verbose )
  {
    OsmPathElement element = new OsmPathElement( path );

    // for final track, cut endnode
    if ( guideTrack != null ) element = element.origin;

    OsmTrack track = new OsmTrack();
    track.cost = path.cost;

    int distance = 0;
    double ascend = 0;
    double ehb = 0.;

    short ele_start = Short.MIN_VALUE;
    short ele_end = Short.MIN_VALUE;
    
    while ( element != null )
    {
      track.addNode( element );
      OsmPathElement nextElement = element.origin;
      
      short ele = element.getSElev();
      if ( ele != Short.MIN_VALUE ) ele_start = ele;
      if ( ele_end == Short.MIN_VALUE ) ele_end = ele;

      if ( nextElement != null )
      {
        distance += element.calcDistance( nextElement );
        short ele_next = nextElement.getSElev();
        if ( ele_next != Short.MIN_VALUE )
        {
          ehb = ehb + (ele - ele_next)/4.;
        }
        if ( ehb > 10. )
        {
          ascend += ehb-10.;
          ehb = 10.;
        }
        else if ( ehb < 0. )
        {
          ehb = 0.;
        }
      }
      element = nextElement ;
    }
    ascend += ehb;
    track.distance = distance;
    track.ascend = (int)ascend;
    track.plainAscend = ( ele_end - ele_start ) / 4;
    logInfo( "track-length = " + track.distance );
    logInfo( "filtered ascend = " + track.ascend );
    track.buildMap();
    return track;
  }

  private OsmTrack mergeTrack( OsmPathElement match, OsmTrack oldTrack )
  {
	  
    OsmPathElement element = match;
    OsmTrack track = new OsmTrack();

    while ( element != null )
    {
      track.addNode( element );
      element = element.origin ;
    }
    long lastId = 0;
    long id1 = match.getIdFromPos();
    long id0 = match.origin == null ? 0 : match.origin.getIdFromPos();
    boolean appending = false;
    for( OsmPathElement n : oldTrack.nodes )
    {
      if ( appending )
      {
        track.nodes.add( n );
      }
    	
      long id = n.getIdFromPos();
      if ( id == id1 && lastId == id0 )
      {
        appending = true;
      }
      lastId = id;
    }
    
    
    track.buildMap();
    return track;
  }

  public int[] getOpenSet()
  {
    synchronized( openSet )
    {
      return openSet.getExtract();
    }
  }

  public boolean isFinished()
  {
    return finished;
  }

  public int getLinksProcessed()
  {
      return linksProcessed;
  }

  public int getDistance()
  {
    return foundTrack.distance;
  }

  public int getAscend()
  {
    return foundTrack.ascend;
  }

  public int getPlainAscend()
  {
    return foundTrack.plainAscend;
  }

  public OsmTrack getFoundTrack()
  {
    return foundTrack;
  }

  public int getAlternativeIndex()
  {
    return alternativeIndex;
  }

  public OsmTrack getFoundRawTrack()
  {
    return foundRawTrack;
  }

  public String getErrorMessage()
  {
    return errorMessage;
  }

  public void terminate()
  {
    terminated = true;
  }
}
