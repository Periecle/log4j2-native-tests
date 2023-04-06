/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.graalvm.log4j2;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Log4j2Tests {

  @Test
  void logging() {
    final Logger logger = LoggerFactory.getLogger(Log4j2Tests.class);

    for (int i = 0; i < 10; i++) {
      logger.info("TEST");
      final var a = 4 + 2;
      logger.warn("412");
      logger.debug("" + a);
      logger.error("55");
    }
  }

}

