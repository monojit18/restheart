/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.db.sessions;

import java.nio.ByteBuffer;
import java.util.UUID;
import static org.restheart.db.sessions.SessionOptions.CAUSALLY_CONSISTENT_FLAG;
import static org.restheart.db.sessions.SessionOptions.TXN_FLAG;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class Sid {

    /**
     * retrieve a type 4 (pseudo randomly generated) UUID.
     *
     * The {@code UUID} is generated using a cryptographically strong pseudo
     * random number generator.
     *
     * @return A randomly generated {@code UUID}
     */
    public static UUID randomUUID() {
        return UUID.randomUUID();
    }

    /**
     * retrieve a type 4 (pseudo randomly generated) UUID, where MSB 3 and 4 of
     * the A byte are used to flag session options
     *
     * The {@code UUID} is generated using a cryptographically strong pseudo
     * random number generator weakend by using 2 bits for flagging.
     *
     * @return A randomly generated {@code UUID}
     */
    public static UUID randomUUID(SessionOptions options) {
        var uuid = UUID.randomUUID();

        var lsb = longToBytes(uuid.getLeastSignificantBits());

        setTransactedFlag(lsb, options.isTransacted());

        setCasuallyConsistentFlag(lsb, options.isCausallyConsistent());

        return new UUID(uuid.getMostSignificantBits(), bytesToLong(lsb));
    }

    public static SessionOptions getSessionOptions(UUID uuid) {
        var lsb = longToBytes(uuid.getLeastSignificantBits());

        boolean tf = (lsb[0] & TXN_FLAG)
                == TXN_FLAG;

        boolean ccf = (lsb[0] & CAUSALLY_CONSISTENT_FLAG)
                == CAUSALLY_CONSISTENT_FLAG;

        return new SessionOptions(tf, ccf);
    }

    /**
     *
     * sets the MSB3 of the UUID A byte<br>
     * <br>
     * UUID format:<br>
     * <br>
     * 123e4567-e89b-42d3-a456-556642440000<br>
     * xxxxxxxx-xxxx-Bxxx-Axxx-xxxxxxxxxxxx<br>
     * <br>
     * Where MSB1 and MSB2 of A are reserverd for UUID variant code<br>
     *
     * @param lsb
     * @param value
     */
    static void setTransactedFlag(byte[] lsb, boolean value) {
        if (value) {
            lsb[0] |= TXN_FLAG; // 0010 0000
        } else {
            lsb[0] &= 0xDF; // 1101 1111
        }
    }

    static void setCasuallyConsistentFlag(byte[] lsb, boolean value) {
        if (value) {
            lsb[0] |= CAUSALLY_CONSISTENT_FLAG; // 0001 0000
        } else {
            lsb[0] &= 0xEF; // 1110 1111
        }
    }

    static byte[] longToBytes(long x) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    static long bytesToLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.put(bytes);
        buffer.flip();//need flip 
        return buffer.getLong();
    }
}