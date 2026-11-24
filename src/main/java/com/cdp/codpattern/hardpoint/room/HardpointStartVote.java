package com.cdp.codpattern.hardpoint.room;

import com.cdp.codpattern.adapter.forge.network.ModNetworkChannel;
import com.cdp.codpattern.app.match.port.VoteControlPort;
import com.cdp.codpattern.app.match.runtime.vote.RoomVoteEngine;
import com.cdp.codpattern.app.match.runtime.vote.RoomVoteEngine.FailureReason;
import com.cdp.codpattern.network.match.VoteDialogPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** A room's whole ready roster must explicitly accept the same start vote, including its initiator. */
final class HardpointStartVote implements VoteControlPort, RoomVoteEngine.Policy<String>, RoomVoteEngine.Listener<String> {
    // The packet has no room key. Keep our IDs distinct across rooms and away from core's counters starting at one.
    private static final AtomicLong WIRE_SEQUENCE = new AtomicLong(2L << 40);
    private final HardpointMap map;
    private final HardpointMatch match;
    private final RoomVoteEngine<String> engine = new RoomVoteEngine<>(this, this);
    private long wireId;
    private Set<UUID> approved;

    HardpointStartVote(HardpointMap map, HardpointMatch match) {
        this.map = map;
        this.match = match;
    }

    OptionalLong activeId() { return wireId == 0 ? OptionalLong.empty() : OptionalLong.of(wireId); }

    @Override public boolean initiateStartVote(UUID initiator) {
        if (initiator == null) return false;
        if (wireId == 0 && match.startProblem(match.members()) == null
                && map.checkGameHasPlayer(initiator) && !match.validStartDeployment()) return false;
        return engine.initiate("START", initiator);
    }
    @Override public boolean initiateEndVote(UUID initiator) { return false; }

    @Override public boolean submitVoteResponse(UUID playerId, long voteId, boolean accepted) {
        var snapshot = engine.activeSnapshot();
        if (snapshot.isEmpty() || voteId != wireId) {
            notifyPlayer(playerId, "message.codpattern.game.vote_expired");
            return false;
        }
        return engine.submit(playerId, snapshot.get().voteId(), accepted);
    }

    @Override public RoomVoteEngine.StartDecision prepareStart(String kind, UUID initiator, boolean active) {
        var members = match.members();
        var voters = ids(members);
        if (initiator == null || !voters.contains(initiator)) return RoomVoteEngine.StartDecision.rejected(FailureReason.INITIATOR_NOT_MEMBER);
        if (active) return RoomVoteEngine.StartDecision.rejected(FailureReason.VOTE_IN_PROGRESS);
        FailureReason problem = match.startProblem(members);
        return problem == null ? RoomVoteEngine.StartDecision.accepted(voters) : RoomVoteEngine.StartDecision.rejected(problem);
    }
    @Override public int requiredVotes(String kind, int members) { return members; }
    @Override public int timeoutTicks(String kind) { return 15 * 20; }
    @Override public RoomVoteEngine.MemberDeparturePolicy memberDeparturePolicy(String kind) {
        return RoomVoteEngine.MemberDeparturePolicy.FAIL_ACTIVE_VOTE;
    }
    @Override public Optional<FailureReason> activeFailure(String kind, Set<UUID> voters) {
        var members = match.members();
        if (!voters.equals(ids(members))) return Optional.of(FailureReason.MEMBER_LEFT);
        return Optional.ofNullable(match.startProblem(members));
    }

    @Override public void onStartRejected(String kind, UUID initiator, FailureReason reason) {
        notifyPlayer(initiator, switch (reason) {
            case VOTE_IN_PROGRESS -> "message.codpattern.game.vote_in_progress";
            case PLAYERS_NOT_READY -> "message.codpattern.vote.players_not_ready";
            case MIN_PLAYERS -> "message.codpattern_hardpoint.both_teams_required";
            case NOT_WAITING -> "message.codpattern.game.already_started";
            default -> "message.codpattern.room.not_in_room";
        });
    }
    @Override public void onResponseRejected(UUID playerId, long voteId, FailureReason reason) {
        notifyPlayer(playerId, reason == FailureReason.ALREADY_VOTED
                ? "message.codpattern.game.already_voted" : "message.codpattern.game.vote_expired");
    }

    @Override public void onStarted(RoomVoteEngine.Snapshot<String> vote) {
        wireId = WIRE_SEQUENCE.incrementAndGet();
        var voters = match.members();
        var initiator = map.getServerLevel().getServer().getPlayerList().getPlayer(vote.initiator());
        var dialog = new VoteDialogPacket(map.getMapName(), wireId, vote.kind(), initiator.getScoreboardName(),
                vote.requiredVotes(), vote.totalMembers());
        // Core closes stale dialogs on snapshots outside START_VOTE. Publish that phase first.
        voters.forEach(map::syncRuntimeState);
        match.broadcast("message.codpattern.game.vote_initiated_start", initiator.getDisplayName());
        voters.forEach(p -> ModNetworkChannel.sendToPlayer(dialog, p));
    }
    @Override public void onProgress(RoomVoteEngine.Snapshot<String> vote) {
        match.broadcast("message.codpattern.game.vote_progress_start", vote.accepted().size(), vote.rejected().size(),
                vote.totalMembers(), vote.requiredVotes());
    }
    @Override public void onPassed(RoomVoteEngine.Snapshot<String> vote) {
        wireId = 0;
        approved = vote.members();
        map.startGame();
    }
    // FPSMatch's public startGame entry only consumes a one-use authorization from the vote engine.
    void startApprovedMatch() {
        if (approved == null) return;
        var voters = approved;
        approved = null;
        match.begin(voters);
    }
    @Override public void onFailed(RoomVoteEngine.Snapshot<String> vote, FailureReason reason) {
        resolved(reason == FailureReason.TIMEOUT ? "message.codpattern.game.vote_timeout_start" : "message.codpattern.game.vote_failed");
    }
    void cancel() {
        if (wireId == 0) return;
        engine.clear();
        resolved("message.codpattern.game.vote_failed");
    }
    void memberLeft(UUID id) { engine.memberDeparted(id); }
    void tick() { engine.tick(); }

    private void resolved(String key) {
        wireId = 0;
        match.broadcast(key);
        match.members().forEach(map::syncRuntimeState);
    }
    private void notifyPlayer(UUID id, String key) {
        if (id == null) return;
        var player = map.getServerLevel().getServer().getPlayerList().getPlayer(id);
        if (player != null) player.sendSystemMessage(Component.translatable(key));
    }
    static Set<UUID> ids(List<ServerPlayer> members) {
        return members.stream().map(ServerPlayer::getUUID).collect(Collectors.toUnmodifiableSet());
    }
}

