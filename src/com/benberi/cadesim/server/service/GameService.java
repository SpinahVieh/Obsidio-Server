package com.benberi.cadesim.server.service;

import com.benberi.cadesim.server.ServerContext;
import com.benberi.cadesim.server.config.Constants;
import com.benberi.cadesim.server.config.ServerConfiguration;
import com.benberi.cadesim.server.model.player.PlayerManager;
import com.benberi.cadesim.server.util.RandomUtils;

/**
 * This is the "heartbeat" main loop of the game server
 */
public class GameService implements Runnable {
    /**
     * The server context
     */
    private ServerContext context;
    private PlayerManager playerManager; // this called so often, should cache
    
    /**
     * Keep track of how many games we've played
     */
    private int gamesCompleted = 0;

    public GameService(ServerContext context) {
        this.context = context;
        this.playerManager = context.getPlayerManager();
    }
    
    /**
     * helper method to randomly rotate the map
     */
    private void randomRotateMap() {
    	ServerConfiguration.setMapName(
    		RandomUtils.getRandomMapName(Constants.mapDirectory)
    	);
    	context.renewMap();
    }

    @Override
    public void run() {
        try {
            context.getPackets().queuePackets();
            context.getTimeMachine().tick();
            playerManager.tick();
            playerManager.queueOutgoing();

            if(playerManager.isGameEnded()) {
            	// print out the scores for the game
            	playerManager.beaconMessageFromServer(
            			"Round ended, final scores were:\n" +
            			"    Defender:" + playerManager.getPointsGreen() + "\n" + 
            			"    Attacker:" + playerManager.getPointsRed()
            	);

            	ServerContext.log("Ending game #" + Integer.toString(gamesCompleted) + ".");
            	gamesCompleted++;
                
                // if no players... hibernate time machines
                if (playerManager.listRegisteredPlayers().size() == 0) {
                    ServerContext.log("No players registered, hibernating to save CPU");
                    while (playerManager.listRegisteredPlayers().size() == 0) {
                        try {
                            Thread.sleep(2000);
                        } catch(InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    ServerContext.log("New player joined, waking up...");
                }
                
                // switch map if players have demanded it, or if we're rotating maps
                String oldMap = ServerConfiguration.getMapName();
                if (playerManager.shouldSwitchMap())
                {
                	randomRotateMap();
                	ServerContext.log(
                		"Players voted to switch map; rotated map to: " +
                		ServerConfiguration.getMapName()
                	);
                }
                else if (
                	(ServerConfiguration.getMapRotationPeriod() > 0) &&
                	((gamesCompleted % ServerConfiguration.getMapRotationPeriod()) == 0)
                ) {
                	randomRotateMap();
                	ServerContext.log(
                		"Rotated map after " +
                		Integer.toString(ServerConfiguration.getMapRotationPeriod()) +
                		" games, automatically chose random map: " +
                		ServerConfiguration.getMapName()
                	);
                }
                
                // message if map changed
                String newMap = ServerConfiguration.getMapName();
                if (!newMap.contentEquals(oldMap))
                {
                	playerManager.beaconMessageFromServer("The map was changed to " + newMap);
                }
                
                // complete the game refresh
                context.getTimeMachine().renewGame();
                playerManager.renewGame();
                playerManager.setGameEnded(false);

                playerManager.beaconMessageFromServer("Started new round: #" + (gamesCompleted + 1));
            }

        } catch (Exception e) {
            e.printStackTrace();
            ServerContext.log(e.getMessage());
        }
    }
}
