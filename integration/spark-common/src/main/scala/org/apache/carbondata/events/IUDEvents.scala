/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.events

import org.apache.carbondata.core.metadata.schema.table.CarbonTable

/**
 *
 */

case class UpdateTablePreEvent(carbonTable: CarbonTable) extends UpdateTableEvent {
  /**
   * Method for getting the event type. Used for invoking all listeners registered for an event
   *
   * @return
   */
  override def getEventType: String = {
    UpdateTablePreEvent.eventType
  }
}

case class DeleteFromTablePreEvent(carbonTable: CarbonTable) extends DeleteFromTableEvent {
  /**
   * Method for getting the event type. Used for invoking all listeners registered for an event
   *
   * @return
   */
  override def getEventType: String = {
    DeleteFromTablePreEvent.eventType
  }
}

object UpdateTablePreEvent {
  val eventType = UpdateTablePreEvent.getClass.getName
}

object DeleteFromTablePreEvent {
  val eventType = DeleteFromTablePreEvent.getClass.getName
}
