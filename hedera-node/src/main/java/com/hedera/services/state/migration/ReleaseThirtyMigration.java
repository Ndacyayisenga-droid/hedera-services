/*
 * Copyright (C) 2022 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hedera.services.state.migration;

import static com.hedera.services.store.models.Id.MISSING_ID;
import static com.hedera.services.txns.crypto.AutoCreationLogic.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.utils.MiscUtils.withLoggedDuration;

import com.hedera.services.ServicesState;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import com.hedera.services.utils.NftNumPair;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.platform.RandomExtended;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ReleaseThirtyMigration {
    private static final Logger log = LogManager.getLogger(ReleaseThirtyMigration.class);
    private static final RandomExtended deterministicNoise = new RandomExtended(8682588012L);

    static final int SEVEN_DAYS_IN_SECONDS = 604800;

    public static void rebuildNftOwners(
            final MerkleMap<EntityNum, MerkleAccount> accounts,
            final MerkleMap<EntityNumPair, MerkleUniqueToken> uniqueTokens) {

        withLoggedDuration(
                () -> {
                    for (final var nftId : uniqueTokens.keySet()) {
                        var nft = uniqueTokens.getForModify(nftId);
                        final var owner = nft.getOwner();
                        final var tokenNum = nftId.getHiOrderAsLong();
                        final var serialNum = nftId.getLowOrderAsLong();

                        if (!owner.equals(EntityId.MISSING_ENTITY_ID)) {
                            var merkleAccount = accounts.getForModify(owner.asNum());

                            if (merkleAccount.getHeadNftTokenNum() != MISSING_ID.num()) {
                                var currHeadNftNum = merkleAccount.getHeadNftTokenNum();
                                var currHeadNftSerialNum = merkleAccount.getHeadNftSerialNum();
                                var currHeadNftId =
                                        EntityNumPair.fromLongs(
                                                currHeadNftNum, currHeadNftSerialNum);
                                var currHeadNft = uniqueTokens.getForModify(currHeadNftId);

                                currHeadNft.setPrev(NftNumPair.fromLongs(tokenNum, serialNum));
                                nft.setNext(
                                        NftNumPair.fromLongs(currHeadNftNum, currHeadNftSerialNum));
                            }
                            merkleAccount.setHeadNftId(tokenNum);
                            merkleAccount.setHeadNftSerialNum(serialNum);
                        }
                    }
                },
                log,
                "NFT owner list rebuild");
    }

    public static void grantFreeAutoRenew(
            final ServicesState initializingState, final Instant lastKnownConsensusTime) {
        final var contracts = initializingState.accounts();
        final var lastKnownConsensusSecond = lastKnownConsensusTime.getEpochSecond();

        withLoggedDuration(
                () ->
                        contracts.forEach(
                                (id, account) -> {
                                    if (account.isSmartContract()) {
                                        setNewExpiry(lastKnownConsensusSecond, contracts, id);
                                    }
                                }),
                log,
                "free contract auto-renewals");
    }

    private static void setNewExpiry(
            final long lastKnownConsensusSecond,
            final MerkleMap<EntityNum, MerkleAccount> contracts,
            final EntityNum key) {
        final var account = contracts.getForModify(key);
        final var currentExpiry = account.getExpiry();
        // Ensure no contract expires within 90 days of the release, but with
        // a little deterministic noise in the exact extension length to avoid
        // many entities expiring in the same second
        final var newExpiry =
                Math.max(
                        currentExpiry,
                        lastKnownConsensusSecond + THREE_MONTHS_IN_SECONDS + wobble());
        account.setExpiry(newExpiry);
    }

    private static long wobble() {
        return deterministicNoise.nextLong(0, SEVEN_DAYS_IN_SECONDS);
    }

    private ReleaseThirtyMigration() {
        throw new UnsupportedOperationException("Utility class");
    }
}
