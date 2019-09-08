package com.benberi.cadesim.server.model.player;

import com.benberi.cadesim.server.ServerContext;
import com.benberi.cadesim.server.codec.packet.out.impl.LoginResponsePacket;
import com.benberi.cadesim.server.config.Constants;
import com.benberi.cadesim.server.model.cade.Team;
import com.benberi.cadesim.server.model.cade.map.flag.Flag;
import com.benberi.cadesim.server.model.player.Vote.VOTE_RESULT;
import com.benberi.cadesim.server.model.player.collision.CollisionCalculator;
import com.benberi.cadesim.server.model.player.domain.PlayerLoginRequest;
import com.benberi.cadesim.server.model.player.move.MoveAnimationTurn;
import com.benberi.cadesim.server.model.player.move.MoveType;
import com.benberi.cadesim.server.model.player.vessel.Vessel;
import com.benberi.cadesim.server.model.player.vessel.VesselMovementAnimation;
import com.benberi.cadesim.server.util.Direction;
import com.benberi.cadesim.server.util.Position;
import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class PlayerManager {

    /**
     * List of players in the game
     */
    private List<Player> players = new ArrayList<>();

    /**
     * Queued players login
     */
    private Queue<PlayerLoginRequest> queuedLoginRequests = new LinkedList<>();

    /**
     * Logout requests
     */
    private Queue<Player> queuedLogoutRequests = new LinkedList<>();

    /**
     * The server context
     */
    private ServerContext context;

    /**
     * The collision calculator
     */
    private CollisionCalculator collision;

    /**
     * The points of the green team
     */
    private int pointsTeamGreen;

    /**
     * The points of the red team
     */
    private int pointsTeamRed;

    /**
     * The last time time packet was sent
     */
    private long lastTimeSend;
    
    /**
     * is a vote in progress
     */
    private Vote currentVote = null;
    
    /**
     * should we switch map? (typically requested by player vote)
     */
    private boolean shouldSwitchMap = false;

	private boolean gameEnded;

    public boolean isGameEnded() {
		return gameEnded;
	}

	public void setGameEnded(boolean gameEnded) {
		this.gameEnded = gameEnded;
	}

	/**
     * constructor
     */
    public PlayerManager(ServerContext context) {
        this.context = context;
        this.collision = new CollisionCalculator(context, this);
    }
    
    /**
     * gets whether the map should be updated
     */
    public boolean shouldSwitchMap() {
    	return this.shouldSwitchMap;
    }
    
    public void setShouldSwitchMap(boolean value) {
    	this.shouldSwitchMap = value;
    }

    /**
     * Ticks all players
     */
    public void tick() {

        // Send time ~ every second
    	long now = System.currentTimeMillis();
        if (now - lastTimeSend >= 1000) {
            lastTimeSend = now;
            handleTime();
            
            // also check for vote results - may have timed out
            // other cases handled elsewhere
            if (currentVote != null)
            {
            	if (currentVote.getResult() == VOTE_RESULT.TIMEDOUT)
            	{
            		handleStopVote();
            		currentVote = null;
            	}
            }
        }

        // Update players (for stuff like damage fixing, bilge fixing and move token generation)
        if (!context.getTimeMachine().isLock()) {
            for (Player p : listRegisteredPlayers()) {
                if (p.isSunk()) {
                    continue;
                }
                p.update();
            }
        }

        // Handle login requests
        if (!context.getTimeMachine().hasLock()) {
            handlePlayerLoginRequests();
            handleLogoutRequests();
        }
    }

    /**
     * Handles and executes all turns
     */
    public void handleTurns() {

        for (Player player : listRegisteredPlayers()) {
            player.getPackets().sendSelectedMoves();
        }

        // Loop through all turns
        for (int turn = 0; turn < 4; turn++) {

            /*
             * Phase 1 handling
             */

            // Loop through phases in the turn (split turn into phases, e.g turn left is 2 phases, turn forward is one phase).
            // So if stopped in phase 1, and its a turn left, it will basically stay in same position, if in phase 2, it will only
            // move one step instead of 2 full steps.
            for (int phase = 0; phase < 2; phase++) {

                 // Go through all players and check if their move causes a collision
                 for (Player p : listRegisteredPlayers()) {

                     // If a player is already collided in this step or is sunk, we don't want him to move
                     // anywhere, so we skip this iteration
                     if (p.getCollisionStorage().isCollided(turn) || p.isSunk()) {
                         continue;
                     }

                     p.getCollisionStorage().setRecursionStarter(true);
                     // Checks collision for the player, according to his current step #phase index and turn
                     collision.checkCollision(p, turn, phase, true);
                     p.getCollisionStorage().setRecursionStarter(false);
                 }

                 // There we update the bumps toggles, if a bump was toggled, we need to save it to the animation storage for this
                 // current turn, and un-toggle it from the collision storage. We separate bump toggles from the main player loop above
                 // Because we have to make sure that bumps been calculated for every one before moving to the next phase
                 for (Player p : listRegisteredPlayers()) {
                     if (p.getCollisionStorage().isBumped()) {
                         p.set(p.getCollisionStorage().getBumpAnimation().getPositionForAnimation(p));
                         p.getCollisionStorage().setBumped(false);
                     }

                     // Toggle last position change save off because we made sure that it was set in the main loop of players
                     p.getCollisionStorage().setPositionChanged(false);
                 }
             }


            // There we save the animations of all bumps, collision and moves of this turn, and clearing the collision storage
            // And what-ever. We also handle shoots there and calculate the turn time
            for (Player p : listRegisteredPlayers()) {
                MoveAnimationTurn t = p.getAnimationStructure().getTurn(turn);
                MoveType move = p.getMoves().getMove(turn);
                p.setFace(move.getNextFace(p.getFace()));
                t.setMoveToken(move);
                if (p.getCollisionStorage().isCollided(turn)) {
                    t.setAnimation(VesselMovementAnimation.getBumpForPhase(p.getCollisionStorage().getCollisionRerefence(turn).getPhase()));
                }
                else {
                    if (p.getCollisionStorage().getBumpAnimation() != VesselMovementAnimation.NO_ANIMATION) {
                        t.setAnimation(p.getCollisionStorage().getBumpAnimation());
                    }
                    else {
                        t.setAnimation(VesselMovementAnimation.getIdForMoveType(move));
                    }
                }

                // Clear the collision storage toggles and such
                p.getCollisionStorage().clear();
            }

            /*
            * Phase 2 handling
            */
            for (int phase = 0; phase < 2; phase++) {
                for (Player player : listRegisteredPlayers()) {
                    if (player.getCollisionStorage().isBumped()) {
                        continue;
                    }

                    int tile = context.getMap().getTile(player.getX(), player.getY());

                    if (player.getCollisionStorage().isOnAction()) {
                        tile = player.getCollisionStorage().getActionTile();
                    }

                    if (context.getMap().isActionTile(tile)) {

                        // Save the action tile
                        if (!player.getCollisionStorage().isOnAction()) {
                            player.getCollisionStorage().setOnAction(tile);
                        }

                        // Next position for action tile
                        Position next = context.getMap().getNextActionTilePosition(tile, player, phase);

                        player.getCollisionStorage().setRecursionStarter(true);
                        collision.checkActionCollision(player, next, turn, phase, true);
                        player.getCollisionStorage().setRecursionStarter(false);
                    }
                }

                for (Player p : listRegisteredPlayers()) {
                    p.getCollisionStorage().setPositionChanged(false);
                }
            }

            for (Player p : listRegisteredPlayers()) {

                if (p.getCollisionStorage().isOnAction()) {
                    //System.out.println(p.getCollisionStorage().getActionTile());
                    int tile = p.getCollisionStorage().getActionTile();
                    if (p.getCollisionStorage().isCollided(turn)) {
                        p.getAnimationStructure().getTurn(turn).setSubAnimation(VesselMovementAnimation.getBumpAnimationForAction(tile));
                    } else {
                        p.getAnimationStructure().getTurn(turn).setSubAnimation(VesselMovementAnimation.getSubAnimation(tile));
                    }

                    if (context.getMap().isWhirlpool(tile))
                        p.setFace(context.getMap().getNextActionTileFace(p.getFace()));
                }

                p.getCollisionStorage().setBumped(false);
                p.getCollisionStorage().clear();
                p.getCollisionStorage().setOnAction(-1);


                // left shoots
                int leftShoots = p.getMoves().getLeftCannons(turn);
                // right shoots
                int rightShoots = p.getMoves().getRightCannons(turn);

                // Apply cannon damages if they collided with anyone
                damagePlayersAtDirection(leftShoots, p, Direction.LEFT, turn);
                damagePlayersAtDirection(rightShoots, p, Direction.RIGHT, turn);

                MoveAnimationTurn t = p.getAnimationStructure().getTurn(turn);

                // Set cannon animations
                t.setLeftShoots(leftShoots);
                t.setRightShoots(rightShoots);

                if (p.getVessel().isDamageMaxed() && !p.isSunk()) {
                    p.setSunk(turn);
                    p.getAnimationStructure().getTurn(turn).setSunk(true);
                }
            }

        }

        // Process some after-turns stuff like updating damage interfaces, and such
        for (Player p : listRegisteredPlayers()) {
            p.processAfterTurnUpdate();
        }

        context.getTimeMachine().setLock(true);
    }

    /**
     * Handles a turn end
     */
    private void handleTurnEnd() {
        calculateInfluenceFlags();
        
	    // end game only after flags calculated, animations done etc
        if (context.getTimeMachine().getGameTime() < 0)
        {
        	setGameEnded(true);
        }
        else
        {
        	context.getTimeMachine().renewTurn();
            sendAfterTurn();
            context.getTimeMachine().setLock(false);
        }
    }

    private void calculateInfluenceFlags() {

        // Reset flags
        context.getMap().resetFlags();

        // Update flags for each player
        for (Player player : listRegisteredPlayers()) {
            if (player.isSunk()) {
                continue;
            }
            List<Flag> flags = context.getMap().getInfluencingFlags(player);
            player.setFlags(flags);
        }


        // Set at war flags
        for (Flag flag : context.getMap().getFlags()) {
            Team addPointsTeam = null;

            Team team = null;
            for (Player p : listRegisteredPlayers()) {
                if (p.isSunk() || p.isNeedsRespawn()) { // bugfix flags are retained on reset
                    continue;
                }
                if (p.getFlags().contains(flag)) {
                    if (team == null) {
                        team = p.getTeam();
                        flag.setControlled(team);
                        addPointsTeam = p.getTeam();
                    }
                    else if (team == p.getTeam()) {
                        addPointsTeam = p.getTeam();
                    }
                    else if (team != p.getTeam()) {
                        flag.setAtWar(true);
                        addPointsTeam = null;
                        break;
                    }
                }
            }

            if (addPointsTeam != null) {
                addPointsToTeam(addPointsTeam, flag.getSize().getID());
            }
        }

    }

    /**
     * Adds points to given team
     *
     * @param team      The team
     * @param points    The points
     */
    private void addPointsToTeam(Team team, int points) {
        switch (team) {
            case GREEN:
                pointsTeamGreen += points;
                break;
            case RED:
                pointsTeamRed += points;
                break;
            case NEUTRAL:
            	ServerContext.log("warning - tried to assign " + Integer.toString(points) + " to NEUTRAL");
            	break;
        }
    }

    /**
     * Damages entities for player's shoot
     * @param shoots        How many shoots to calculate
     * @param source        The shooting vessel instance
     * @param direction     The shoot direction
     */
    private void damagePlayersAtDirection(int shoots, Player source, Direction direction, int turnId) {
        if (shoots <= 0) {
            return;
        }
        Player player = collision.getVesselForCannonCollide(source, direction);
        if (player != null && source.isOutOfSafe() && !source.isSunk()) {
            player.getVessel().appendDamage(((double) shoots * source.getVessel().getCannonType().getDamage()), source.getTeam());
        }
    }

    /**
     * Handles the time
     * TODO there must be a way to parallelise this - e.g. just add constant onto everyone's time
     * TODO so that the timeouts dont stack up
     */
    private void handleTime() {
        if (context.getTimeMachine().hasLock()) {
            boolean failed = false;
            for (Player p : listRegisteredPlayers()) {
                if (!p.isTurnFinished()) {
                    if (p.getTurnFinishWaitingTicks() > Constants.TURN_FINISH_TIMEOUT) {
                        ServerContext.log(p.getName() +  " kicked for timing out while animating!");
                        beaconMessageFromServer(p.getName() + " from team " + p.getTeam() + " was kicked for timing out.");
                        p.getChannel().disconnect();
                    }
                    else {
                        p.updateTurnFinishWaitingTicks();
                        failed = true;
                    }
                }
            }

            if (!failed) {
                handleTurnEnd();
            }
        }

        sendTime();
    }

    /**
     * Gets a player for given position
     * @param x The X-axis position
     * @param y The Y-xis position
     * @return The player instance if found, null if not
     */
    public Player getPlayerByPosition(int x, int y) {
        for (Player p : listRegisteredPlayers()) {
            if (p.getX() == x && p.getY() == y) {
                return p;
            }
        }
        return  null;
    }

    /**
     * Sends a player's move bar to everyone
     *
     * @param pl    The player's move to send
     */
    public void sendMoveBar(Player pl) {
        for (Player p : listRegisteredPlayers()) {
            p.getPackets().sendMoveBar(pl);
        }
    }

    /**
     * Lists all registered players
     *
     * @return Sorted list of {@link #players} with only registered players
     */
    public List<Player> listRegisteredPlayers() {
        List<Player> registered = new ArrayList<>();
        for (Player p : players) {
            if (p.isRegistered()) {
                registered.add(p);
            }
        }

        return registered;
    }

    /**
     * Gets ALL players
     * @return  the players
     */
    public List<Player> getPlayers() {
        return this.players;
    }

    /**
     * Registers a new player to the server, puts him in a hold until he sends the protocol handshake packet
     *
     * @param c The channel to register
     */
    public void registerPlayer(Channel c) {
        Player player = new Player(context, c);
        players.add(player);
        ServerContext.log("New player registered on channel " + c.remoteAddress() + " (registered players: " + listRegisteredPlayers().size() + ")");
    }


    /**
     * De-registers a player from the server
     *
     * @param channel   The channel that got de-registered
     */
    public void deRegisterPlayer(Channel channel) {
        Player player = getPlayerByChannel(channel);
        if (player != null) {
            player.setTurnFinished(true);
            queuedLogoutRequests.add(player);
            ServerContext.log("Player " + player.getName() + " de-registered on " + channel.remoteAddress() + " (players remaining: " + (listRegisteredPlayers().size() - 1) + ")");
        }
        else {
            ServerContext.log("Channel DE-registered but player object was not found: " + channel.remoteAddress() + " (players remaining: " + (listRegisteredPlayers().size() - 1) + ")");
            players.remove(player); // try to remove them regardless
        }
    }

    /**
     * Reset all move bars
     */
    public void resetMoveBars() {
        for (Player p : listRegisteredPlayers()) {
            sendMoveBar(p);
            p.getAnimationStructure().reset();
        }
    }

    /**
     * Gets a player instance by its channel
     * @param c The channel
     * @return  The player instance if found, null if not found
     */
    public Player getPlayerByChannel(Channel c) {
        for(Player p : players) {
            if (p.getChannel().equals(c)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Gets a player by its name
     * @param name  The player name
     * @return The player
     */
    public Player getPlayerByName(String name) {
        for (Player p : listRegisteredPlayers()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Sends a player for all players
     *
     * @param player    The player to send
     */
    public void sendPlayerForAll(Player player) {
        for (Player p : players) {
            if (p == player) {
                continue;
            }
            p.getPackets().sendPlayer(player);
        }
    }

    /**
     * Queues a player login request
     *
     * @param request   The login request
     */
    public void queuePlayerLogin(PlayerLoginRequest request) {
        queuedLoginRequests.add(request);
    }

    /**
     * Handles all player login requests
     */
    public void handlePlayerLoginRequests() {
        while(!queuedLoginRequests.isEmpty()) {
            PlayerLoginRequest request = queuedLoginRequests.poll();

            Player pl = request.getPlayer();
            String name = request.getName();
            int version = request.getVersion();
            int ship = request.getShip();
            int team = request.getTeam();

            int response = LoginResponsePacket.SUCCESS;

            if (version != Constants.PROTOCOL_VERSION) {
                response = LoginResponsePacket.BAD_VERSION;
            }
            else if (getPlayerByName(name) != null) {
                response = LoginResponsePacket.NAME_IN_USE;
            }
            else if (
            	(name.contains(Constants.bannedSubstring)) ||
            	(name.length() <= 0) ||
            	(name.length() > Constants.MAX_NAME_SIZE))
            {
            	response = LoginResponsePacket.BAD_NAME;
            }
            else if (!Vessel.vesselExists(ship)) {
                response = LoginResponsePacket.BAD_SHIP;
            }

            pl.getPackets().sendLoginResponse(response);

            if (response == LoginResponsePacket.SUCCESS) {
                pl.register(name, ship, team);
                pl.getPackets().sendBoard();
                pl.getPackets().sendTeams();
                pl.getPackets().sendPlayers();
                pl.getPackets().sendDamage();
                pl.getPackets().sendTokens();
                pl.getPackets().sendFlags();
                pl.getPackets().sendPlayerFlags();
                sendPlayerForAll(pl);
                beaconMessageFromServer("Welcome " + pl.getName() + " (" + pl.getTeam() + ", " + pl.getVessel().getName() + ")");
                // If a new player joins and there are now 2 players in the server, end this round so a new one will start
                if (listRegisteredPlayers().size() == 2) {
                	beaconMessageFromServer("There are now 2 players in the sim. Starting a new round");
                    context.getTimeMachine().endGame();
                    context.getTimeMachine().endTurn();
                }
            }
        }
    }

    /**
     * Handles logouts
     */
    private void handleLogoutRequests() {
        while(!queuedLogoutRequests.isEmpty()) {
            Player player = queuedLogoutRequests.poll();

            for (Player p : listRegisteredPlayers()) {
                if (p != player) {
                    p.getPackets().sendRemovePlayer(player);
                }
            }

            beaconMessageFromServer("Goodbye " + player.getName() + " (" + player.getTeam() + ", " + player.getVessel().getName() + ")");
            players.remove(player);
        }
    }

    /**
     * Sends and updates the time of the game, turn for all players
     */
    private void sendTime() {

        for (Player player : listRegisteredPlayers()) {
            player.getPackets().sendTime();
        }
    }

    public void resetSunkShips() {
        for (Player p : listRegisteredPlayers()) {
            if (p.isSunk()) {
                p.giveLife();
            }

            sendMoveBar(p);
            p.getAnimationStructure().reset();
        }
    }

    public void sendAfterTurn() {

        for (Player p : listRegisteredPlayers()) {
            if (p.isNeedsRespawn()) {
                p.respawn();
            }

            p.getAnimationStructure().reset();
            p.setTurnFinished(false);
            p.resetWaitingTicks();
        }

        for (Player p : listRegisteredPlayers()) {
            p.getPackets().sendPositions();
            p.getPackets().sendFlags();
            p.getPackets().sendPlayerFlags();
            if (p.isSunk()) {
                p.giveLife();
            }
            sendMoveBar(p);

        }
    }

    public int getPointsGreen() {
        return pointsTeamGreen;
    }

    public int getPointsRed() {
        return pointsTeamRed;
    }

    public void queueOutgoing() {
        for (Player p : players) {
            p.getPackets().queueOutgoingPackets();
            p.getChannel().flush();
        }
    }

    public void renewGame()
    {
    	shouldSwitchMap = false;
        pointsTeamRed = 0;
        pointsTeamGreen = 0;

        for (Player p : listRegisteredPlayers()) {
        	p.setFirstEntry(true);
        	p.setNeedsRespawn(true);
        	p.getPackets().sendBoard();
        	p.getMoveTokens().setAutomaticSealGeneration(true); // bugfix disparity between client and server
        	p.getPackets().sendFlags();
        }
    }
    
    /**
     * send message to all players from server
     */
    public void beaconMessageFromServer(String message)
    {
    	ServerContext.log("[chat] " + Constants.serverBroadcast + ":" + message);
        for(Player player : context.getPlayerManager().listRegisteredPlayers()) {
            player.getPackets().sendReceiveMessage(Constants.serverBroadcast, message);
        }
    }
    
    /**
     * send a message to a single player from the server
     */
    public void serverMessage(Player pl, String message)
    {
    	ServerContext.log("[chat] " + Constants.serverPrivate + "(to " + pl.getName() + "):" + message);
        pl.getPackets().sendReceiveMessage(Constants.serverPrivate, message);
    }
    
    /**
     * helper method to handle vote yes/no messages
     * @param pl      the player who sent the message
     * @param message either /yes or /no
     */
    private void handleVote(Player pl, String message)
    {
    	// subsequent people (including originator) may vote on it
		boolean voteFor = (message.equals("/yes"));
		if (currentVote == null)
		{
			serverMessage(pl, "There is no vote in progress");
		}
		else if (currentVote.getDescription().equals("restart"))
		{
			VOTE_RESULT v = currentVote.castVote(pl, voteFor);
			switch(v)
			{
			case TBD:
				break;
			case FOR:
				handleStopVote();
				context.getTimeMachine().endGame();
                context.getTimeMachine().endTurn();
				break;
			case AGAINST:
			case TIMEDOUT:
				handleStopVote();
				break;
			default:
				break;
			}
		}
		else if (currentVote.getDescription().equals("nextmap"))
		{
			VOTE_RESULT v = currentVote.castVote(pl, voteFor);
			switch(v)
			{
			case TBD:
				break;
			case FOR:
				handleStopVote();
				setShouldSwitchMap(true);
				context.getTimeMachine().endGame();
                context.getTimeMachine().endTurn();
				break;
			case AGAINST:
			case TIMEDOUT:
				handleStopVote();
				break;
			default:
				break;
			}
		}
    }
    
    private void handleStartVote(Player pl, String message, String voteDescription)
    {
    	// first person to request vote creates it (and votes for it)
		if (currentVote == null)
		{
			currentVote = new Vote((PlayerManager)this, voteDescription);
			handleVote(pl, "/yes");
		}
		else
		{
			serverMessage(pl, "Can't start a new vote, there is one in progress, use /yes or /no to vote: " + currentVote.getDescription());
		}
    }
    
    private void handleStopVote()
    {
    	// print out the final scores
    	if (currentVote != null)
    	{
    		currentVote.getResult();
    		currentVote = null;
    	}
    }
    
    public void handleMessage(Player pl, String message)
    {
    	// log here (always)
        ServerContext.log("[chat] " + pl.getName() + ":" + message);

    	// if it starts with /, it is a server command
		if (message.startsWith("/"))
		{
			// cleanup
			message = message.toLowerCase();

			// voting on a current vote
			if (message.equals("/yes") || message.equals("/no"))
			{
				handleVote(pl, message);				
			}
			else if (message.equals("/restart"))
    		{
				handleStartVote(pl, message, "restart");
    		}
    		else if (message.equals("/nextmap"))
    		{
    			handleStartVote(pl, message, "nextmap");
    		}
			else
			{
				serverMessage(pl, "commands: [/nextmap, /restart, /yes, /no]");
			}
		}
		else
		{
			// dont beacon server commands, but beacon everything else
			for(Player player : context.getPlayerManager().listRegisteredPlayers()) {
	            player.getPackets().sendReceiveMessage(pl.getName(), message);
	        }
		}
    }
}
