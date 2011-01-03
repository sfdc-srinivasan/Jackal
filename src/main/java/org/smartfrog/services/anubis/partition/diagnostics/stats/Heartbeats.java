/** (C) Copyright 1998-2005 Hewlett-Packard Development Company, LP

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

For more information: www.smartfrog.org

 */
package org.smartfrog.services.anubis.partition.diagnostics.stats;

public class Heartbeats {

    private AveCalculator oneHourCalculator = new TimedAveCalculator(
                                                                     60 * 60 * 1000);
    private AveCalculator oneMinuteCalculator = new TimedAveCalculator(
                                                                       60 * 1000);
    private AveCalculator tenMinuteCalculator = new TimedAveCalculator(
                                                                       10 * 60 * 1000);

    public Heartbeats() {
    }

    public void add(long time, long delay) {
        oneMinuteCalculator.add(time, delay);
        tenMinuteCalculator.add(time, delay);
        oneHourCalculator.add(time, delay);
    }

    public long oneHourAve() {
        return oneHourCalculator.average();
    }

    public long oneMinAve() {
        return oneMinuteCalculator.average();
    }

    public long tenMinAve() {
        return tenMinuteCalculator.average();
    }

}