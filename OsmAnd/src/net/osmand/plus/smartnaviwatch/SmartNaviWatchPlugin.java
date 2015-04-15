package net.osmand.plus.smartnaviwatch;

import android.app.Activity;
import android.util.Log;
import net.osmand.Location;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.R;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.util.MapUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import ch.hsr.navigationmessagingapi.IMessageListener;
import ch.hsr.navigationmessagingapi.MessageDataKeys;
import ch.hsr.navigationmessagingapi.MessageTypes;
import ch.hsr.navigationmessagingapi.NavigationMessage;
import ch.hsr.navigationmessagingapi.services.NavigationServiceConnector;

public class SmartNaviWatchPlugin extends OsmandPlugin implements IMessageListener{

    // Connections to the OsmAnd Application Framework
    private OsmandApplication application;
    private RoutingHelper routing;
    private OsmAndLocationProvider location;

    // Connection to the Messaging API
    private NavigationServiceConnector messageService;

    // Data with information about the current routing progress
    private RouteCalculationResult.NextDirectionInfo currentInfo;
    private Location lastKnownLocation;
    private boolean hasNotified = false;

    /**
     * Removes all data from the current navigation status
     */
    private void cleanRouteData() {
        lastKnownLocation = null;
        currentInfo = null;
        hasNotified = false;
    }

    /**
     * Gets a connector to a navigation service that can send and receive messages
     * @return API connector
     */
    private NavigationServiceConnector getServiceConnector() {
        if (messageService == null)
        {
            messageService = new NavigationServiceConnector(application.getApplicationContext());
        }
        return messageService;
    }

    /**
     * Compares the two passed navigation steps and returns true if they are different
     * @param info1 First step, can be null
     * @param info2 Second step, can be null
     * @return boolean, that indicates if a difference was found
     */
    private boolean stepsAreDifferent(RouteCalculationResult.NextDirectionInfo info1, RouteCalculationResult.NextDirectionInfo info2)
    {
        // Difference by null
        if ((info1 != null && info2 == null) || info1 == null && info2 != null) return true;

        // Not different because of reference equality
        if (info1 == info2) return false;

        // If the other checks failed, check for route point offset
        return info1.directionInfo.routePointOffset != info2.directionInfo.routePointOffset;
    }

    /**
     * Checks for a necessity to update the user and the infos about the current step
     */
    private void updateNavigationSteps() {
        RouteCalculationResult.NextDirectionInfo n = routing.getNextRouteDirectionInfo(new RouteCalculationResult.NextDirectionInfo(), false);

        // Check if the next step changed
        if(stepsAreDifferent(currentInfo, n)) {
            currentInfo = n;
            hasNotified = false;

          if (currentInfo != null) application.showShortToastMessage("new step: " + currentInfo.directionInfo.getDescriptionRoute(application));
        }

        // Check distance to the current step, if smaller than a certain radius,
        // tell the user to execute the step
        if (currentInfo != null && lastKnownLocation != null) {
            Location p1 = routing.getRoute().getLocationFromRouteDirection(currentInfo.directionInfo);

            // Closer than 15m? Then notify the user.
            double delta = MapUtils.getDistance(p1.getLatitude(), p1.getLongitude(), lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
            if (delta < 15 && !hasNotified) {
                sendMessage(MessageTypes.NextStepMessage, createCurrentStepBundle(currentInfo.directionInfo));
                hasNotified = true;
            }
        }
    }

    /**
     * Called when a message from the navigation message api is received
     * @param message Message passed from the API
     */
    @Override
    public void messageReceived(NavigationMessage message) {
        switch(message.getMessageType()) {
            case MessageTypes.PositionRequest:
                findLocationAndRespond();
                break;
            default:
                findLocationAndRespond();

                break;
        }
    }

    /**
     * Finds the current location of the user and gets an appropriate street name / location description
     */
    private void findLocationAndRespond() {
        // Read map and show it to the user
        BinaryMapIndexReader[] readers = application.getResourceManager().getRoutingMapFiles();

        if(readers.length > 0 && lastKnownLocation != null) {
            BinaryMapIndexReader reader = readers[0];

            // Calculate distance to the next navigation step
            double distanceToNextPoint = 100;
            double mapBorderSpacing = 150;
            if (currentInfo != null) {
                Location p2 = routing.getRoute().getLocationFromRouteDirection(currentInfo.directionInfo);
                distanceToNextPoint = MapUtils.getDistance(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude(), p2.getLatitude(), p2.getLongitude());
            }
            double visibleRange = distanceToNextPoint + mapBorderSpacing;

            BinaryMapIndexReader.SearchRequest<BinaryMapDataObject> request = buildRequestAround(lastKnownLocation, visibleRange);

            List<BinaryMapDataObject> res = null;
            try {
                 res = reader.searchMapIndex(request);
            } catch(IOException ex) {}

            if (res != null){
                application.showToastMessage(res.size()+"");
                for(BinaryMapDataObject o : res) {
                    Log.d("object found: ", o.getName());
                    for(int i = 0; i < o.getTypes().length; i++) {
                        BinaryMapIndexReader.TagValuePair p = o.getMapIndex().decodeType(o.getTypes()[i]);

                        Log.d("object type: ", p.toString());
                    }
                }
            }
        }
    }

    /**
     * Creates a SearchRequest that searches a square (side = 2*distanceInMeters) with it's center
     * aligned to the specified location
     * @param loc Location of the center of the square
     * @param distanceInMeters Half of the length of the square
     * @return SearchRequest
     */
    private BinaryMapIndexReader.SearchRequest<BinaryMapDataObject> buildRequestAround(Location loc, double distanceInMeters) {
        // Calculate the upper left and lower right corners of the map part
        int x31Distance = (int)Math.ceil(distanceInMeters / MapUtils.convert31XToMeters(1,0));
        int y31Distance = (int)Math.ceil(distanceInMeters / MapUtils.convert31YToMeters(1,0));

        int leftX = MapUtils.get31TileNumberX(lastKnownLocation.getLongitude()) - x31Distance;
        int rightX = MapUtils.get31TileNumberX(lastKnownLocation.getLongitude()) + x31Distance;
        int topY = MapUtils.get31TileNumberY(lastKnownLocation.getLatitude()) - y31Distance;
        int bottomY = MapUtils.get31TileNumberY(lastKnownLocation.getLatitude()) + y31Distance;
        return BinaryMapIndexReader.buildSearchRequest(leftX, rightX, topY, bottomY, 15, null);
    }

    /**
     * Adapts to routing events (start and end of navigation)
     */
    private class RoutingAdapter implements RoutingHelper.IRouteInformationListener {

        /**
         * Adapts to routing events (start and end of navigation)
         * @param newRoute Indicates wether a completely new route or an alternate route to an existing
         *                 route was used.
         */
        @Override
        public void newRouteIsCalculated(boolean newRoute) {
            cleanRouteData();

            // Get the first instruction
            List<RouteDirectionInfo> directionInfos = routing.getRouteDirections();
            if (directionInfos.size() > 0) {
                RouteDirectionInfo firstStep = directionInfos.get(0);

                sendMessage(MessageTypes.NewRouteMessage, createCurrentStepBundle(firstStep));
            }
        }

        /**
         * Navigation has ended
         */
        @Override
        public void routeWasCancelled() {
            cleanRouteData();

            //sendMessage(MessageTypes.CancellationMessage, null);
        }
    }

    /**
     * Sends a new message via the connected API
     * @param messageType Type of the message
     * @param payload Content of the message
     */
    private void sendMessage(String messageType, Object payload) {
        application.showToastMessage("send: " + messageType);
        NavigationMessage msg = NavigationMessage.create(messageType, payload);
        getServiceConnector().sendMessage(msg);
    }

    /**
     * Creates a HashMap of data from the route direction info
     * @param info Information about the next step
     * @return Bundle filled with the data
     */
    private HashMap<String, Object> createCurrentStepBundle(RouteDirectionInfo info) {
        double percentage = (double)routing.getRoute().getDistanceToPoint(info.routePointOffset) / (double)routing.getRoute().getWholeDistance();

        HashMap<String, Object> m = new HashMap<String, Object>();
        m.put(MessageDataKeys.TurnType, info.getTurnType().toString());
        m.put(MessageDataKeys.TurnAngle, info.getTurnType().getTurnAngle());
        m.put(MessageDataKeys.Distance, info.distance); // evtl. via currentInfo.distanceTo
        m.put(MessageDataKeys.StreetName, info.getStreetName());
        m.put(MessageDataKeys.RouteProgressPercentage, percentage);
        return m;
    }

    /**
     * Adapts to the location service from OsmAnd. Receives updates when the users location
     * changes.
     */
    private class LocationAdapter implements OsmAndLocationProvider.OsmAndLocationListener{
        /**
         * New location is known. Store it and check if updates should be sent to the user
         * @param location Location info
         */
        @Override
        public void updateLocation(Location location) {
            if (locationChangeIsSignificant(lastKnownLocation, location)) {
                lastKnownLocation = location;


                updateNavigationSteps();
            }
        }
    }

    /**
     * Checks wether the two locations differ significantly from each other or not
     * @param lastKnownLocation Location 1
     * @param location Location 2
     * @return True if significant change found
     */
    private boolean locationChangeIsSignificant(Location lastKnownLocation, Location location) {
        if (lastKnownLocation == null && location != null) return true;
        if (lastKnownLocation != null && location == null) return true;
        if (lastKnownLocation == null && location == null) return false;

        double delta = MapUtils.getDistance(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude(), location.getLatitude(), location.getLongitude());

        return delta >= 1;
    }

    /**
     * Creates a new instance of SmartNaviWatchPlugin
     * @param app OsmAnd application this plugin runs in
     */
    public SmartNaviWatchPlugin(OsmandApplication app)
    {
        application = app;
    }

    /**
     * Initializes the plugin and creates references to the OsmAnd and NavigationAPI services
     * @param app OsmAnd application this plugin runs in
     * @param activity Activity that initialized the plugin
     * @return Initialization success
     */
    @Override
    public boolean init(OsmandApplication app, Activity activity) {
        // Listen for new routes and cancellation
        routing = app.getRoutingHelper();
        routing.addListener(new RoutingAdapter());

        // Listen for location updates
        location = app.getLocationProvider();
        location.addLocationListener(new LocationAdapter());

        // Listen to messages
        getServiceConnector().addMessageListener(this);

        return true;
    }

    /**
     * Gets the activity that should be started for additional settings. Not Used.
     * @return null
     */
    @Override
    public Class<? extends Activity> getSettingsActivity() {
        return null;
    }

    /**
     * Gets the plugin id
     * @return Plugin id
     */
    @Override
    public String getId() {
        return "ch.hsr.smart-navi-watch";
    }

    /**
     * Gets the plugin description
     * @return Plugin description
     */
    @Override
    public String getDescription() {
        return "Sends data to your watch.";
    }

    /**
     * Gets the display name of the plugin
     * @return Plugin display name
     */
    @Override
    public String getName() {
        return "Smart Navi Watch";
    }

    /**
     * Gets the asset name for the icon
     * @return Icon asset name
     */
    @Override
    public int getAssetResourceName() {
        return R.drawable.parking_position;
    }
}
