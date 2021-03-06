package com.benberi.cadesim.server.codec.packet.in;

import com.benberi.cadesim.server.ServerContext;
import com.benberi.cadesim.server.codec.util.Packet;
import com.benberi.cadesim.server.model.player.Player;
import com.benberi.cadesim.server.model.player.domain.PlayerLoginRequest;
import com.benberi.cadesim.server.codec.packet.ServerPacketExecutor;

public class PlayerLoginPacket extends ServerPacketExecutor {

    public PlayerLoginPacket(ServerContext ctx) {
        super(ctx);
    }

    @Override
    public void execute(Player pl, Packet p) {
        int version = p.readByte();
        int ship = p.readByte();
        int team = p.readByte();
        String name = p.readByteString();

        getContext().getPlayerManager().queuePlayerLogin(new PlayerLoginRequest(pl, name, ship, team, version));
    }
}
