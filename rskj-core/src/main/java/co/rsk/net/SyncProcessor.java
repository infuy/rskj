package co.rsk.net;

import co.rsk.core.bc.BlockChainStatus;
import co.rsk.net.messages.*;
import com.google.common.annotations.VisibleForTesting;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.ethereum.core.BlockIdentifier;
import org.ethereum.core.Blockchain;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by ajlopez on 29/08/2017.
 */
public class SyncProcessor {
    private long nextId;
    private Blockchain blockchain;
    private Map<NodeID, Status> peers = new HashMap<>();
    private Map<NodeID, SyncPeerStatus> peerStatuses = new HashMap<>();
    private Map<Long, NodeID> pendingResponses = new HashMap<>();
    private Map<Long, PendingBodyResponse> pendingBodyResponses = new HashMap<>();

    public SyncProcessor(Blockchain blockchain) {
        this.blockchain = blockchain;
    }

    public int getNoPeers() {
        return this.peers.size();
    }

    public int getNoAdvancedPeers() {
        BlockChainStatus chainStatus = this.blockchain.getStatus();

        if (chainStatus == null)
            return this.peers.size();

        BigInteger totalDifficulty = chainStatus.getTotalDifficulty();
        int count = 0;

        for (Status status : this.peers.values())
            if (status.getTotalDifficulty().compareTo(totalDifficulty) > 0)
                count++;

        return count;
    }

    public void processStatus(MessageSender sender, Status status) {
        peers.put(sender.getNodeID(), status);

        if (status.getTotalDifficulty().compareTo(this.blockchain.getTotalDifficulty()) > 0)
            this.findConnectionPoint(sender, status.getBestBlockNumber());
    }

    public void sendSkeletonRequest(MessageSender sender, long height) {
        sender.sendMessage(new SkeletonRequestMessage(++nextId, height));
    }

    public void processSkeletonResponse(MessageSender sender, SkeletonResponseMessage message) {
        SyncPeerStatus peerStatus = this.getPeerStatus(sender.getNodeID());

        peerStatus.setBlockIdentifiers(message.getBlockIdentifiers());

        long bestNumber = this.blockchain.getStatus().getBestBlockNumber();

        for (BlockIdentifier bi : message.getBlockIdentifiers()) {
            long height = bi.getNumber();

            if (height > bestNumber) {
                int count = (int)(height - bestNumber);
                sender.sendMessage(new BlockHeadersRequestMessage(++nextId, bi.getHash(), count));
                return;
            }
        }
    }

    public void sendBlockHashRequest(MessageSender sender, long height) {
        sender.sendMessage(new BlockHashRequestMessage(++nextId, height));
    }

    public void findConnectionPoint(MessageSender sender, long height) {
        SyncPeerStatus peerStatus = this.createPeerStatus(sender.getNodeID());
        peerStatus.startFindConnectionPoint(height);
        this.sendBlockHashRequest(sender, peerStatus.getFindingHeight());
    }

    public void processBlockHashResponse(MessageSender sender, BlockHashResponseMessage message) {
        SyncPeerStatus peerStatus = this.getPeerStatus(sender.getNodeID());

        Block block = this.blockchain.getBlockByHash(message.getHash());

        if (block != null)
            peerStatus.updateFound();
        else
            peerStatus.updateNotFound();

        if (peerStatus.hasConnectionPoint()) {
            sendSkeletonRequest(sender, peerStatus.getConnectionPoint());
            return;
        }

        sendBlockHashRequest(sender, peerStatus.getFindingHeight());
    }

    public void processBlockHeadersResponse(MessageSender sender, BlockHeadersResponseMessage message) {
        // to validate:
        // - PoW
        // - Parent exists
        // - consecutive numbers
        // - consistent difficulty

        NodeID expectedNodeId = pendingResponses.get(message.getId());
        if (sender.getNodeID() != expectedNodeId) {
            // Don't waste time on spam or expired responses.
            return;
        }

        // to do: decide whether we have to request the body immediately if we don't have it,
        // or maybe only after we have validated it
        List<BlockHeader> headers = message.getBlockHeaders();
        for (BlockHeader header : headers) {
            if (this.blockchain.getBlockByHash(header.getHash()) == null) {
                sender.sendMessage(new BlockRequestMessage(++nextId, header.getHash()));
            }
        }

        pendingResponses.remove(message.getId());
    }

    public void processBodyResponse(MessageSender sender, BodyResponseMessage message) {
        // TODO(mc):
        // 1. validate not spam
        // 2. retrieve header of the body we're expecting
        // 3. validate transactions and uncles are part of this block (with header)
        // 4. enqueue block for validation (i.e. we need to have the parent block)

        PendingBodyResponse expected = pendingBodyResponses.get(message.getId());
        if (expected == null || sender.getNodeID() != expected.nodeID) {
            // Don't waste time on spam or expired responses.
            return;
        }

        // TODO(mc): reuse NodeBlockProcessor.processBlock
        this.blockchain.tryToConnect(new Block(expected.header, message.getTransactions(), message.getUncles()));
    }

    public SyncPeerStatus createPeerStatus(NodeID nodeID) {
        SyncPeerStatus peerStatus = new SyncPeerStatus();
        peerStatuses.put(nodeID, peerStatus);
        return peerStatus;
    }

    public SyncPeerStatus getPeerStatus(NodeID nodeID) {
        SyncPeerStatus peerStatus = this.peerStatuses.get(nodeID);

        if (peerStatus != null)
            return peerStatus;

        return this.createPeerStatus(nodeID);
    }

    @VisibleForTesting
    public void expectMessageFrom(long requestId, NodeID nodeID) {
        pendingResponses.put(requestId, nodeID);
    }

    @VisibleForTesting
    public void expectBodyResponseFor(long requestId, NodeID nodeID, BlockHeader header) {
        pendingBodyResponses.put(requestId, new PendingBodyResponse(nodeID, header));
    }

    private static class PendingBodyResponse {
        private NodeID nodeID;
        private BlockHeader header;

        PendingBodyResponse(NodeID nodeID, BlockHeader header) {
            this.nodeID = nodeID;
            this.header = header;
        }
    }
}
