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
package com.hedera.services.state.virtual;

import com.hedera.test.serde.SelfSerializableDataTest;
import com.hedera.test.utils.SeededPropertySource;

public class VirtualBlobKeySerdeTest extends SelfSerializableDataTest<VirtualBlobKey> {
    @Override
    protected Class<VirtualBlobKey> getType() {
        return VirtualBlobKey.class;
    }

    @Override
    protected VirtualBlobKey getExpectedObject(final SeededPropertySource propertySource) {
        return propertySource.nextVirtualBlobKey();
    }
}
