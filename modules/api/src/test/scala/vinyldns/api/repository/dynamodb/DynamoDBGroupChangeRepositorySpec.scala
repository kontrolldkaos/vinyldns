/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.repository.dynamodb

import com.amazonaws.services.dynamodbv2.model.{GetItemRequest, ResourceNotFoundException, _}
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import vinyldns.api.domain.membership.{GroupChange, ListGroupChangesResults}
import vinyldns.api.{GroupTestData, ResultHelpers, VinylDNSConfig}

import scala.collection.JavaConverters._
import scala.concurrent.Future

class DynamoDBGroupChangeRepositorySpec
    extends WordSpec
    with MockitoSugar
    with Matchers
    with GroupTestData
    with ResultHelpers
    with ScalaFutures
    with BeforeAndAfterEach {

  private implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))
  private val dynamoDBHelper = mock[DynamoDBHelper]
  private val groupChangeStoreConfig = VinylDNSConfig.groupChangesStoreConfig
  private val groupChangeTable =
    VinylDNSConfig.groupChangesStoreConfig.getString("dynamo.tableName")
  class TestDynamoDBGroupChangeRepository
      extends DynamoDBGroupChangeRepository(groupChangeStoreConfig, dynamoDBHelper)

  private val underTest = new DynamoDBGroupChangeRepository(groupChangeStoreConfig, dynamoDBHelper)

  override def beforeEach(): Unit = {
    reset(dynamoDBHelper)
    doNothing().when(dynamoDBHelper).setupTable(any[CreateTableRequest])
  }

  "DynamoDBGroupChangeRepository constructor" should {

    "call setup table when it is built" in {
      val setupTableCaptor = ArgumentCaptor.forClass(classOf[CreateTableRequest])

      new TestDynamoDBGroupChangeRepository
      verify(dynamoDBHelper).setupTable(setupTableCaptor.capture())

      val createTable = setupTableCaptor.getValue

      createTable.getTableName shouldBe groupChangeTable
      (createTable.getAttributeDefinitions should contain).only(underTest.tableAttributes: _*)
      createTable.getKeySchema.get(0).getAttributeName shouldBe underTest.GROUP_CHANGE_ID
      createTable.getKeySchema.get(0).getKeyType shouldBe KeyType.HASH.toString
      createTable.getGlobalSecondaryIndexes.toArray() shouldBe underTest.secondaryIndexes.toArray
      createTable.getProvisionedThroughput.getReadCapacityUnits shouldBe 30L
      createTable.getProvisionedThroughput.getWriteCapacityUnits shouldBe 30L
    }

    "fail when an exception is thrown setting up the table" in {

      doThrow(new RuntimeException("fail")).when(dynamoDBHelper).setupTable(any[CreateTableRequest])

      a[RuntimeException] should be thrownBy new TestDynamoDBGroupChangeRepository
    }
  }

  "DynamoDBGroupChangeRepository.toItem and fromItem" should {
    "work with all values set" in {
      val roundRobin = underTest.fromItem(underTest.toItem(okGroupChangeUpdate))
      roundRobin shouldBe okGroupChangeUpdate
    }

    "work with oldGroup = None" in {
      val roundRobin = underTest.fromItem(underTest.toItem(okGroupChange))
      roundRobin shouldBe okGroupChange
    }
  }

  "DynamoDBGroupChangeRepository.save" should {
    "return the group change when saved" in {
      val mockPutItemResult = mock[PutItemResult]

      doReturn(Future.successful(mockPutItemResult))
        .when(dynamoDBHelper)
        .putItem(any[PutItemRequest])

      val response = await[GroupChange](underTest.save(okGroupChange))

      response shouldBe okGroupChange
    }
    "throw exception when save returns an unexpected response" in {
      doReturn(Future.failed(new ResourceNotFoundException("bar does not exist")))
        .when(dynamoDBHelper)
        .putItem(any[PutItemRequest])

      val result = underTest.save(okGroupChange)
      whenReady(result.failed) { failed =>
        failed shouldBe a[ResourceNotFoundException]
      }
    }
  }

  "DynamoDBGroupChangeRepository.getGroupChange" should {
    "return the group change if the id is found" in {
      val dynamoResponse = mock[GetItemResult]

      val expected = underTest.toItem(okGroupChange)
      doReturn(expected).when(dynamoResponse).getItem
      doReturn(Future.successful(dynamoResponse)).when(dynamoDBHelper).getItem(any[GetItemRequest])

      val response = await[Option[GroupChange]](underTest.getGroupChange(okGroupChange.id))

      verify(dynamoDBHelper).getItem(any[GetItemRequest])

      response shouldBe Some(okGroupChange)
    }
    "throw exception when get returns an unexpected response" in {
      doReturn(Future.failed(new ResourceNotFoundException("bar does not exist")))
        .when(dynamoDBHelper)
        .getItem(any[GetItemRequest])

      val result = underTest.getGroupChange(okGroupChange.id)
      whenReady(result.failed) { failed =>
        failed shouldBe a[ResourceNotFoundException]
      }
    }
    "return None if not found" in {
      val dynamoResponse = mock[GetItemResult]
      doReturn(null).when(dynamoResponse).getItem
      doReturn(Future.successful(dynamoResponse)).when(dynamoDBHelper).getItem(any[GetItemRequest])

      val response = await[Option[GroupChange]](underTest.getGroupChange(okGroupChange.id))

      verify(dynamoDBHelper).getItem(any[GetItemRequest])

      response shouldBe None
    }
  }
  "DynamoDBGroupChangeRepository.getGroupChanges" should {
    "returns all matching GroupChanges and the correct nextId" in {
      val dynamoResponse = mock[QueryResult]

      val expected = listOfDummyGroupChanges.slice(0, 100).map(underTest.toItem).asJava
      doReturn(expected).when(dynamoResponse).getItems()

      val lastEvaluatedKey = underTest.toItem(listOfDummyGroupChanges(99))
      doReturn(lastEvaluatedKey).when(dynamoResponse).getLastEvaluatedKey

      doReturn(Future.successful(dynamoResponse)).when(dynamoDBHelper).query(any[QueryRequest])

      val response =
        await[ListGroupChangesResults](underTest.getGroupChanges(oneUserDummyGroup.id, None, 100))

      response.changes should contain theSameElementsAs listOfDummyGroupChanges.take(100)
      response.lastEvaluatedTimeStamp shouldBe Some(
        listOfDummyGroupChanges(99).created.getMillis.toString)
    }
    "returns an empty list when no matching changes are found" in {
      val dynamoResponse = mock[QueryResult]

      val expected = List().asJava
      doReturn(expected).when(dynamoResponse).getItems()

      doReturn(Future.successful(dynamoResponse)).when(dynamoDBHelper).query(any[QueryRequest])

      val response =
        await[ListGroupChangesResults](underTest.getGroupChanges(oneUserDummyGroup.id, None, 100))

      response.changes shouldBe Seq()
      response.lastEvaluatedTimeStamp shouldBe None
    }
    "starts from the correct change" in {
      val dynamoGetResponse = mock[GetItemResult]

      doReturn(underTest.toItem(listOfDummyGroupChanges(50))).when(dynamoGetResponse).getItem
      doReturn(Future.successful(dynamoGetResponse))
        .when(dynamoDBHelper)
        .getItem(any[GetItemRequest])

      val dynamoQueryResponse = mock[QueryResult]

      val expected = listOfDummyGroupChanges.slice(51, 151).map(underTest.toItem).asJava
      doReturn(expected).when(dynamoQueryResponse).getItems()

      val lastEvaluatedKey = underTest.toItem(listOfDummyGroupChanges(150))
      doReturn(lastEvaluatedKey).when(dynamoQueryResponse).getLastEvaluatedKey

      doReturn(Future.successful(dynamoQueryResponse)).when(dynamoDBHelper).query(any[QueryRequest])

      val response = await[ListGroupChangesResults](
        underTest.getGroupChanges(
          oneUserDummyGroup.id,
          Some(listOfDummyGroupChanges(50).created.getMillis.toString),
          100))

      response.changes should contain theSameElementsAs listOfDummyGroupChanges.slice(51, 151)
      response.lastEvaluatedTimeStamp shouldBe Some(
        listOfDummyGroupChanges(150).created.getMillis.toString)
    }
    "returns `maxItems` items" in {
      val dynamoResponse = mock[QueryResult]

      val expected = listOfDummyGroupChanges.slice(0, 50).map(underTest.toItem).asJava
      doReturn(expected).when(dynamoResponse).getItems()

      val lastEvaluatedKey = underTest.toItem(listOfDummyGroupChanges(49))
      doReturn(lastEvaluatedKey).when(dynamoResponse).getLastEvaluatedKey

      doReturn(Future.successful(dynamoResponse)).when(dynamoDBHelper).query(any[QueryRequest])

      val response =
        await[ListGroupChangesResults](underTest.getGroupChanges(oneUserDummyGroup.id, None, 50))

      response.changes should contain theSameElementsAs listOfDummyGroupChanges.take(50)
      response.lastEvaluatedTimeStamp shouldBe Some(
        listOfDummyGroupChanges(49).created.getMillis.toString)
    }

    "returns entire page and nextId = None if there are less than maxItems left" in {
      val dynamoGetResponse = mock[GetItemResult]

      doReturn(underTest.toItem(listOfDummyGroupChanges(99))).when(dynamoGetResponse).getItem
      doReturn(Future.successful(dynamoGetResponse))
        .when(dynamoDBHelper)
        .getItem(any[GetItemRequest])

      val dynamoQueryResponse = mock[QueryResult]

      val expected = listOfDummyGroupChanges.slice(100, 200).map(underTest.toItem).asJava
      doReturn(expected).when(dynamoQueryResponse).getItems()

      doReturn(Future.successful(dynamoQueryResponse)).when(dynamoDBHelper).query(any[QueryRequest])

      val response = await[ListGroupChangesResults](
        underTest.getGroupChanges(oneUserDummyGroup.id, Some(listOfDummyGroupChanges(99).id), 100))

      response.changes should contain theSameElementsAs (listOfDummyGroupChanges.slice(100, 200))
      response.lastEvaluatedTimeStamp shouldBe None
    }
  }
}
