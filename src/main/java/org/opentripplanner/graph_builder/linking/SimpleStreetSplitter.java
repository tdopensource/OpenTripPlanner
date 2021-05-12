package org.opentripplanner.graph_builder.linking;

import com.google.common.collect.Iterables;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.SpatialIndex;
import org.locationtech.jts.linearref.LinearLocation;
import org.locationtech.jts.linearref.LocationIndexedLine;
import gnu.trove.map.TIntDoubleMap;
import gnu.trove.map.hash.TIntDoubleHashMap;
import jersey.repackaged.com.google.common.collect.Lists;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.common.geometry.HashGridSpatialIndex;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.common.model.GenericLocation;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.graph_builder.annotation.BikeParkUnlinked;
import org.opentripplanner.graph_builder.annotation.BikeRentalStationUnlinked;
import org.opentripplanner.graph_builder.annotation.StopLinkedTooFar;
import org.opentripplanner.graph_builder.annotation.StopUnlinked;
import org.opentripplanner.graph_builder.services.DefaultStreetEdgeFactory;
import org.opentripplanner.graph_builder.services.StreetEdgeFactory;
import org.opentripplanner.openstreetmap.model.OSMWithTags;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.edgetype.AreaEdge;
import org.opentripplanner.routing.edgetype.AreaEdgeList;
import org.opentripplanner.routing.edgetype.StreetBikeParkLink;
import org.opentripplanner.routing.edgetype.StreetBikeRentalLink;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.edgetype.StreetTransitLink;
import org.opentripplanner.routing.edgetype.StreetTraversalPermission;
import org.opentripplanner.routing.edgetype.TemporaryFreeEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.location.TemporaryStreetLocation;
import org.opentripplanner.routing.vertextype.BikeParkVertex;
import org.opentripplanner.routing.vertextype.BikeRentalStationVertex;
import org.opentripplanner.routing.vertextype.IntersectionVertex;
import org.opentripplanner.routing.vertextype.SplitterVertex;
import org.opentripplanner.routing.vertextype.StreetVertex;
import org.opentripplanner.routing.vertextype.TemporarySplitterVertex;
import org.opentripplanner.routing.vertextype.TemporaryVertex;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.opentripplanner.util.I18NString;
import org.opentripplanner.util.LocalizedString;
import org.opentripplanner.util.NonLocalizedString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * This class links transit stops to streets by splitting the streets (unless the stop is extremely close to the street
 * intersection).
 *
 * It is intended to eventually completely replace the existing stop linking code, which had been through so many
 * revisions and adaptations to different street and turn representations that it was very glitchy. This new code is
 * also intended to be deterministic in linking to streets, independent of the order in which the JVM decides to
 * iterate over Maps and even in the presence of points that are exactly halfway between multiple candidate linking
 * points.
 *
 * It would be wise to keep this new incarnation of the linking code relatively simple, considering what happened before.
 *
 * See discussion in pull request #1922, follow up issue #1934, and the original issue calling for replacement of
 * the stop linker, #1305.
 */
public class SimpleStreetSplitter {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleStreetSplitter.class);

    public static final int MAX_SEARCH_RADIUS_METERS = 1000;

    private Boolean addExtraEdgesToAreas = false;

    private StreetEdgeFactory edgeFactory;

    public static final int WARNING_DISTANCE_METERS = 20;

    /** if there are two ways and the distances to them differ by less than this value, we link to both of them */
    public static final double DUPLICATE_WAY_EPSILON_METERS = 0.001;

    private Graph graph;

    private HashGridSpatialIndex<Edge> idx;

    private SpatialIndex transitStopIndex;

    private static GeometryFactory geometryFactory = GeometryUtils.getGeometryFactory();

    //If true edges are split and new edges are created (used when linking transit stops etc. during graph building)
    //If false new temporary edges are created and no edges are deleted (Used when searching for origin/destination)
    private final boolean destructiveSplitting;

    private static final Comparator<StreetWithDistanceAdapter> STREET_WITH_DISTANCE_ADAPTER_COMPARATOR = (o1, o2) -> {
        double diff = o1.distance - o2.distance;
        // A Comparator must return an integer but our distances are doubles.
        if (diff < 0)
            return -1;
        if (diff > 0)
            return 1;
        return 0;
    };

    /**
     * Construct a new SimpleStreetSplitter.
     * NOTE: Only one SimpleStreetSplitter should be active on a graph at any given time.
     *
     * @param hashGridSpatialIndex If not null this index is used instead of creating new one
     * @param transitStopIndex Index of all transitStops which is generated in {@link org.opentripplanner.routing.impl.StreetVertexIndexServiceImpl}
     * @param destructiveSplitting If true splitting is permanent (Used when linking transit stops etc.) when false Splitting is only for duration of a request. Since they are made from temporary vertices and edges.
     */
    public SimpleStreetSplitter(Graph graph, HashGridSpatialIndex<Edge> hashGridSpatialIndex,
                                SpatialIndex transitStopIndex, boolean destructiveSplitting) {
        this.graph = graph;
        this.transitStopIndex = transitStopIndex;
        this.destructiveSplitting = destructiveSplitting;
        this.edgeFactory = new DefaultStreetEdgeFactory();

        //We build a spatial index if it isn't provided
        if (hashGridSpatialIndex == null) {
            // build a nice private spatial index, since we're adding and removing edges
            idx = new HashGridSpatialIndex<>();
            for (StreetEdge se : Iterables.filter(graph.getEdges(), StreetEdge.class)) {
                idx.insert(se.getGeometry(), se);
            }
        } else {
            idx = hashGridSpatialIndex;
        }

    }

    /**
     * Construct a new SimpleStreetSplitter. Be aware that only one SimpleStreetSplitter should be
     * active on a graph at any given time.
     *
     * SimpleStreetSplitter generates index on graph and splits destructively (used in transit splitter)
     * @param graph
     */
    public SimpleStreetSplitter(Graph graph) {
        this(graph, null, null, true);
    }

    /** Link all relevant vertices to the street network */
    public void link () {	
        for (Vertex v : graph.getVertices()) {
            if (v instanceof TransitStop || v instanceof BikeRentalStationVertex || v instanceof BikeParkVertex)
                if (!link(v)) {
                    if (v instanceof TransitStop)
                        LOG.warn(graph.addBuilderAnnotation(new StopUnlinked((TransitStop) v)));
                    else if (v instanceof BikeRentalStationVertex)
                        LOG.warn(graph.addBuilderAnnotation(new BikeRentalStationUnlinked((BikeRentalStationVertex) v)));
                    else if (v instanceof BikeParkVertex)
                        LOG.warn(graph.addBuilderAnnotation(new BikeParkUnlinked((BikeParkVertex) v)));
                };
        }
    }

    static class StreetWithDistanceAdapter{
        final StreetEdge streetEdge;
        final double distance;

        StreetWithDistanceAdapter(final StreetEdge streetEdge, final double distance){
            this.streetEdge = streetEdge;
            this.distance = distance;
        }

    }

    /** Link this vertex into the graph to the closest walkable edge */
    public boolean link (Vertex vertex) {
        return link(vertex, TraverseMode.WALK, null);
    }

    void sortThreeMinElementsOnly(final List<StreetWithDistanceAdapter> candidateEdges) {
        if (!candidateEdges.isEmpty()) {
            List<StreetWithDistanceAdapter> minElements = new ArrayList<>(2);

            StreetWithDistanceAdapter min1 = Collections.min(candidateEdges, STREET_WITH_DISTANCE_ADAPTER_COMPARATOR);
            candidateEdges.remove(min1);
            minElements.add(min1);

            if (!candidateEdges.isEmpty()) {
                StreetWithDistanceAdapter min2 = Collections.min(candidateEdges, STREET_WITH_DISTANCE_ADAPTER_COMPARATOR);
                candidateEdges.remove(min2);
                minElements.add(min2);
            }
            if (!candidateEdges.isEmpty()) {
                StreetWithDistanceAdapter min3 = Collections.min(candidateEdges, STREET_WITH_DISTANCE_ADAPTER_COMPARATOR);
                candidateEdges.remove(min3);
                minElements.add(min3);
            }

            candidateEdges.addAll(0, minElements);
        }
    }

    /** Link this vertex into the graph */
    public boolean link(Vertex vertex, TraverseMode traverseMode, RoutingRequest options) {
        // find nearby street edges
        // TODO: we used to use an expanding-envelope search, which is more efficient in
        // dense areas. but first let's see how inefficient this is. I suspect it's not too
        // bad and the gains in simplicity are considerable.
        final double radiusDeg = SphericalDistanceLibrary.metersToDegrees(MAX_SEARCH_RADIUS_METERS);

        Envelope env = new Envelope(vertex.getCoordinate());

        // Perform a simple local equirectangular projection, so distances are expressed in degrees latitude.
        final double xscale = Math.cos(vertex.getLat() * Math.PI / 180);

        // Expand more in the longitude direction than the latitude direction to account for converging meridians.
        env.expandBy(radiusDeg / xscale, radiusDeg);

        final double DUPLICATE_WAY_EPSILON_DEGREES = SphericalDistanceLibrary.metersToDegrees(DUPLICATE_WAY_EPSILON_METERS);

        final TraverseModeSet traverseModeSet;
        if (traverseMode == TraverseMode.BICYCLE) {
            traverseModeSet = new TraverseModeSet(traverseMode, TraverseMode.WALK);
        } else {
            traverseModeSet = new TraverseModeSet(traverseMode);
        }
        // We sort the list of candidate edges by distance to the stop
        // This should remove any issues with things coming out of the spatial index in different orders
        // Then we link to everything that is within DUPLICATE_WAY_EPSILON_METERS of of the best distance
        // so that we capture back edges and duplicate ways.
        List<StreetWithDistanceAdapter> candidateEdges = idx.query(env).stream()
                .filter(streetEdge -> streetEdge instanceof  StreetEdge)
                .map(edge -> (StreetEdge) edge)
                // note: not filtering by radius here as distance calculation is expensive
                // we do that below.
                .filter(edge -> edge.canTraverse(traverseModeSet) &&
                        // only link to edges still in the graph.
                        edge.getToVertex().getIncoming().contains(edge))
                .map(e -> new StreetWithDistanceAdapter(e,  distance(vertex, e, xscale)))
                .collect(Collectors.toList());

        sortThreeMinElementsOnly(candidateEdges);

        // find the closest candidate edges
        if (candidateEdges.isEmpty() || candidateEdges.get(0).distance > radiusDeg) {
            // We only link to stops if we are searching for origin/destination and for that we need transitStopIndex.
            if (destructiveSplitting || transitStopIndex == null) {
                return false;
            }
            LOG.debug("No street edge was found for {}", vertex);
            // We search for closest stops (since this is only used in origin/destination linking if no edges were found)
            // in the same way the closest edges are found.
            List<TransitStop> candidateStops = new ArrayList<>();
            transitStopIndex.query(env).forEach(candidateStop ->
                    candidateStops.add((TransitStop) candidateStop)
            );

            final TIntDoubleMap stopDistances = new TIntDoubleHashMap();

            for (TransitStop t : candidateStops) {
                stopDistances.put(t.getIndex(), distance(vertex, t, xscale));
            }

            Collections.sort(candidateStops, (o1, o2) -> {
                double diff = stopDistances.get(o1.getIndex()) - stopDistances.get(o2.getIndex());
                if (diff < 0) {
                    return -1;
                }
                if (diff > 0) {
                    return 1;
                }
                return 0;
            });
            if (candidateStops.isEmpty() || stopDistances.get(candidateStops.get(0).getIndex()) > radiusDeg) {
                LOG.debug("Stops aren't close either!");
                return false;
            } else {
                List<TransitStop> bestStops = Lists.newArrayList();
                // Add stops until there is a break of epsilon meters.
                // we do this to enforce determinism. if there are a lot of stops that are all extremely close to each other,
                // we want to be sure that we deterministically link to the same ones every time. Any hard cutoff means things can
                // fall just inside or beyond the cutoff depending on floating-point operations.
                int i = 0;
                do {
                    bestStops.add(candidateStops.get(i++));
                } while (i < candidateStops.size() &&
                        stopDistances.get(candidateStops.get(i).getIndex()) - stopDistances
                                .get(candidateStops.get(i - 1).getIndex()) < DUPLICATE_WAY_EPSILON_DEGREES);

                for (TransitStop stop: bestStops) {
                    LOG.debug("Linking vertex to stop: {}", stop.getName());
                    makeTemporaryEdges((TemporaryStreetLocation)vertex, stop);
                }
                return true;
            }
        } else {

            // find the best edges
            List<StreetEdge> bestEdges = Lists.newArrayList();

            // add edges until there is a break of epsilon meters.
            // we do this to enforce determinism. if there are a lot of edges that are all extremely close to each other,
            // we want to be sure that we deterministically link to the same ones every time. Any hard cutoff means things can
            // fall just inside or beyond the cutoff depending on floating-point operations.
            int i = 0;
            do {
                if (i == 2) {
                    LOG.debug("link - sorting edges. Single call is ok but many this calls affect performance");
                    // few first elements was sorted manually, we have postpone full sorting to this place because of performance.
                    // only for 16  from 1000 calls reached i==2 what gives us significant performance boost
                    Collections.sort(candidateEdges, STREET_WITH_DISTANCE_ADAPTER_COMPARATOR);
                }
                bestEdges.add(candidateEdges.get(i++).streetEdge);
            } while (i < candidateEdges.size() && candidateEdges.get(i).distance - candidateEdges.get(i - 1).distance < DUPLICATE_WAY_EPSILON_DEGREES);

            for (StreetEdge edge : bestEdges) {
                link(vertex, edge, xscale, options);
            }

            // Warn if a linkage was made, but the linkage was suspiciously long.
            if (vertex instanceof TransitStop) {
                double distanceDegreesLatitude = candidateEdges.get(0).distance;
                int distanceMeters = (int)SphericalDistanceLibrary.degreesLatitudeToMeters(distanceDegreesLatitude);
                if (distanceMeters > WARNING_DISTANCE_METERS) {
                    // Registering an annotation but not logging because tests produce thousands of these warnings.
                    graph.addBuilderAnnotation(new StopLinkedTooFar((TransitStop)vertex, distanceMeters));
                }
            }

            return true;
        }
    }

    // Link to all vertices in area/platform
    private void linkTransitToAreaVertices(Vertex splitterVertex, AreaEdgeList area) {
        List<Vertex> vertices = new ArrayList<>();

        for (AreaEdge areaEdge : area.getEdges()) {
            if (!vertices.contains(areaEdge.getToVertex())) vertices.add(areaEdge.getToVertex());
            if (!vertices.contains(areaEdge.getFromVertex())) vertices.add(areaEdge.getFromVertex());
        }

        for (Vertex vertex : vertices) {
            if (vertex instanceof  StreetVertex && !vertex.equals(splitterVertex)) {
                LineString line = geometryFactory.createLineString(new Coordinate[] { splitterVertex.getCoordinate(), vertex.getCoordinate()});
                double length = SphericalDistanceLibrary.distance(splitterVertex.getCoordinate(),
                        vertex.getCoordinate());
                I18NString name = new LocalizedString("", new OSMWithTags());

                edgeFactory.createAreaEdge((IntersectionVertex) splitterVertex, (IntersectionVertex) vertex, line, name, length,StreetTraversalPermission.PEDESTRIAN_AND_BICYCLE, false, area);
                edgeFactory.createAreaEdge((IntersectionVertex) vertex, (IntersectionVertex) splitterVertex, line, name, length,StreetTraversalPermission.PEDESTRIAN_AND_BICYCLE, false, area);
            }
        }
    }

    /** split the edge and link in the transit stop */
    private void link(Vertex tstop, StreetEdge edge, double xscale, RoutingRequest options) {
        // TODO: we've already built this line string, we should save it
        LineString orig = edge.getGeometry();
        LineString transformed = equirectangularProject(orig, xscale);
        LocationIndexedLine il = new LocationIndexedLine(transformed);
        LinearLocation ll = il.project(new Coordinate(tstop.getLon() * xscale, tstop.getLat()));

        // if we're very close to one end of the line or the other, or endwise, don't bother to split,
        // cut to the chase and link directly
        // We use a really tiny epsilon here because we only want points that actually snap to exactly the same location on the
        // street to use the same vertices. Otherwise the order the stops are loaded in will affect where they are snapped.
        if (ll.getSegmentIndex() == 0 && ll.getSegmentFraction() < 1e-8) {
            makeLinkEdges(tstop, (StreetVertex) edge.getFromVertex());
        }
        // -1 converts from count to index. Because of the fencepost problem, npoints - 1 is the "segment"
        // past the last point
        else if (ll.getSegmentIndex() == orig.getNumPoints() - 1) {
            makeLinkEdges(tstop, (StreetVertex) edge.getToVertex());
        }

        // nPoints - 2: -1 to correct for index vs count, -1 to account for fencepost problem
        else if (ll.getSegmentIndex() == orig.getNumPoints() - 2 && ll.getSegmentFraction() > 1 - 1e-8) {
            makeLinkEdges(tstop, (StreetVertex) edge.getToVertex());
        }

        else {

            TemporaryVertex temporaryVertex = null;
            boolean endVertex = false;
            if (tstop instanceof TemporaryVertex) {
                temporaryVertex = (TemporaryVertex) tstop;
                endVertex = temporaryVertex.isEndVertex();

            }
            //This throws runtime TrivialPathException if same edge is split in origin and destination link
            //It is only used in origin/destination linking since otherwise options is null
            if (options != null) {
                options.canSplitEdge(edge);
            }
            // split the edge, get the split vertex
            SplitterVertex v0 = split(edge, ll, temporaryVertex != null, endVertex);
            makeLinkEdges(tstop, v0);

            // If splitter vertex is part of area; link splittervertex to all other vertexes in area, this creates
            // edges that were missed by WalkableAreaBuilder
            if (edge instanceof AreaEdge && tstop instanceof TransitStop && this.addExtraEdgesToAreas) {
                linkTransitToAreaVertices(v0, ((AreaEdge) edge).getArea());
            }
        }
    }


    /**
     * Split the street edge at the given fraction
     *
     * @param edge to be split
     * @param ll fraction at which to split the edge
     * @param temporarySplit if true this is temporary split at origin/destinations search and only temporary edges vertices are created
     * @param endVertex if this is temporary edge this is true if this is end vertex otherwise it doesn't matter
     * @return Splitter vertex with added new edges
     */
    private SplitterVertex split (StreetEdge edge, LinearLocation ll, boolean temporarySplit, boolean endVertex) {
        LineString geometry = edge.getGeometry();

        // create the geometries
        Coordinate splitPoint = ll.getCoordinate(geometry);

        // every edge can be split exactly once, so this is a valid label
        SplitterVertex v;
        if (temporarySplit) {
            v = new TemporarySplitterVertex("split from " + edge.getId(), splitPoint.x, splitPoint.y, edge, endVertex);
        } else {
            v = new SplitterVertex(graph, "split from " + edge.getId(), splitPoint.x, splitPoint.y, edge);
        }

        // Split the 'edge' at 'v' in 2 new edges and connect these 2 edges to the
        // existing vertices
        P2<StreetEdge> edges = edge.split(v, !temporarySplit);

        if (destructiveSplitting) {
            // update indices of new edges
            idx.insert(edges.first.getGeometry(), edges.first);
            idx.insert(edges.second.getGeometry(), edges.second);

            // (no need to remove original edge, we filter it when it comes out of the index)

            // remove original edge from the graph
            edge.getToVertex().removeIncoming(edge);
            edge.getFromVertex().removeOutgoing(edge);
        }

        return v;
    }

    /** Make the appropriate type of link edges from a vertex */
    private void makeLinkEdges(Vertex from, StreetVertex to) {
        if (from instanceof TemporaryStreetLocation) {
            makeTemporaryEdges((TemporaryStreetLocation) from, to);
        } else if (from instanceof TransitStop) {
            makeTransitLinkEdges((TransitStop) from, to);
        } else if (from instanceof BikeRentalStationVertex) {
            makeBikeRentalLinkEdges((BikeRentalStationVertex) from, to);
        } else if (from instanceof BikeParkVertex) {
            makeBikeParkEdges((BikeParkVertex) from, to);
        }
    }

    /** Make temporary edges to origin/destination vertex in origin/destination search **/
    private void makeTemporaryEdges(TemporaryStreetLocation from, Vertex to) {
        if (destructiveSplitting) {
            throw new RuntimeException("Destructive splitting is used on temporary edges. Something is wrong!");
        }
        if (to instanceof TemporarySplitterVertex) {
            from.setWheelchairAccessible(((TemporarySplitterVertex) to).isWheelchairAccessible());
        }
        if (from.isEndVertex()) {
            LOG.debug("Linking end vertex to {} -> {}", to, from);
            new TemporaryFreeEdge(to, from);
        } else {
            LOG.debug("Linking start vertex to {} -> {}", from, to);
            new TemporaryFreeEdge(from, to);
        }
    }

    /** Make bike park edges */
    private void makeBikeParkEdges(BikeParkVertex from, StreetVertex to) {
        if (!destructiveSplitting) {
            throw new RuntimeException("Bike park edges are created with non destructive splitting!");
        }
        for (StreetBikeParkLink sbpl : Iterables.filter(from.getOutgoing(), StreetBikeParkLink.class)) {
            if (sbpl.getToVertex() == to)
                return;
        }

        new StreetBikeParkLink(from, to);
        new StreetBikeParkLink(to, from);
    }

    /**
     * Make street transit link edges, unless they already exist.
     */
    private void makeTransitLinkEdges (TransitStop tstop, StreetVertex v) {
        if (!destructiveSplitting) {
            throw new RuntimeException("Transitedges are created with non destructive splitting!");
        }
        // ensure that the requisite edges do not already exist
        // this can happen if we link to duplicate ways that have the same start/end vertices.
        for (StreetTransitLink e : Iterables.filter(tstop.getOutgoing(), StreetTransitLink.class)) {
            if (e.getToVertex() == v)
                return;
        }

        new StreetTransitLink(tstop, v, tstop.hasWheelchairEntrance());
        new StreetTransitLink(v, tstop, tstop.hasWheelchairEntrance());
    }

    /** Make link edges for bike rental */
    private void makeBikeRentalLinkEdges (BikeRentalStationVertex from, StreetVertex to) {
        if (!destructiveSplitting) {
            throw new RuntimeException("Bike rental edges are created with non destructive splitting!");
        }
        for (StreetBikeRentalLink sbrl : Iterables.filter(from.getOutgoing(), StreetBikeRentalLink.class)) {
            if (sbrl.getToVertex() == to)
                return;
        }

        new StreetBikeRentalLink(from, to);
        new StreetBikeRentalLink(to, from);
    }

    /** projected distance from stop to edge, in latitude degrees */
    private static double distance (Vertex tstop, StreetEdge edge, double xscale) {
        // Despite the fact that we want to use a fast somewhat inaccurate projection, still use JTS library tools
        // for the actual distance calculations.
        LineString transformed = equirectangularProject(edge.getGeometry(), xscale);
        return transformed.distance(geometryFactory.createPoint(new Coordinate(tstop.getLon() * xscale, tstop.getLat())));
    }

    /** projected distance from stop to another stop, in latitude degrees */
    private static double distance (Vertex tstop, Vertex tstop2, double xscale) {
        // use JTS internal tools wherever possible
        return new Coordinate(tstop.getLon() * xscale, tstop.getLat()).distance(new Coordinate(tstop2.getLon() * xscale, tstop2.getLat()));
    }

    /** project this linestring to an equirectangular projection */
    private static LineString equirectangularProject(LineString geometry, double xscale) {
        Coordinate[] coords = new Coordinate[geometry.getNumPoints()];

        for (int i = 0; i < coords.length; i++) {
            Coordinate c = geometry.getCoordinateN(i);
            c = (Coordinate) c.clone();
            c.x *= xscale;
            coords[i] = c;
        }

        return geometryFactory.createLineString(coords);
    }

    /**
     * Used to link origin and destination points to graph non destructively.
     *
     * Split edges don't replace existing ones and only temporary edges and vertices are created.
     *
     * Will throw ThrivialPathException if origin and destination Location are on the same edge
     *
     * @param location
     * @param options
     * @param endVertex true if this is destination vertex
     * @return
     */
    public Vertex getClosestVertex(GenericLocation location, RoutingRequest options,
                                   boolean endVertex) {
        if (destructiveSplitting) {
            throw new RuntimeException("Origin and destination search is used with destructive splitting. Something is wrong!");
        }
        if (endVertex) {
            LOG.debug("Finding end vertex for {}", location);
        } else {
            LOG.debug("Finding start vertex for {}", location);
        }
        Coordinate coord = location.getCoordinate();
        //TODO: add nice name
        String name;

        if (location.name == null || location.name.isEmpty()) {
            if (endVertex) {
                name = "Destination";
            } else {
                name = "Origin";
            }
        } else {
            name = location.name;
        }
        TemporaryStreetLocation closest = new TemporaryStreetLocation(UUID.randomUUID().toString(),
                coord, new NonLocalizedString(name), endVertex);

        TraverseMode nonTransitMode = TraverseMode.WALK;
        //It can be null in tests
        if (options != null) {
            TraverseModeSet modes = options.modes;
            if (modes.getCar())
                // for park and ride we will start in car mode and walk to the end vertex
                if (endVertex && (options.parkAndRide || options.kissAndRide)) {
                    nonTransitMode = TraverseMode.WALK;
                } else {
                    nonTransitMode = TraverseMode.CAR;
                }
            else if (modes.getWalk())
                nonTransitMode = TraverseMode.WALK;
            else if (modes.getBicycle())
                nonTransitMode = TraverseMode.BICYCLE;
        }

        if(!link(closest, nonTransitMode, options)) {
            LOG.warn("Couldn't link {}", location);
        }
        return closest;

    }

    public Boolean getAddExtraEdgesToAreas() {
        return addExtraEdgesToAreas;
    }

    public void setAddExtraEdgesToAreas(Boolean addExtraEdgesToAreas) {
        this.addExtraEdgesToAreas = addExtraEdgesToAreas;
    }
}
