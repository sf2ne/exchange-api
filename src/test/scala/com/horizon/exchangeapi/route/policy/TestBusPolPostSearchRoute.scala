package com.horizon.exchangeapi.route.policy

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.headers.CacheDirectives.public
import akka.stream.ActorMaterializer
import com.horizon.exchangeapi.ExchangeApiApp.{cpds, db, logger}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, BusinessPolicyNodeResponse, BusinessRoutes, ExchConfig, ExchangeApi, HttpCode, PostBusinessPolicySearchRequest, PostBusinessPolicySearchResponse, PostPutBusinessPolicyRequest, PostPutOrgRequest, PostPutServiceRequest, PostPutUsersRequest, PutAgbotsRequest, PutNodesRequest, Role, TestDBConnection, tables}
import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, BService, BServiceVersions, BusinessPoliciesTQ, BusinessPolicyRow, NodeAgreementRow, NodeAgreementsTQ, NodeHeartbeatIntervals, NodeRow, NodeType, NodesTQ, OneProperty, OneUserInputService, OneUserInputValue, OrgRow, OrgsTQ, Prop, RegService, ResourceChangesTQ, SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ, ServiceRow, ServicesTQ, UserRow, UsersTQ}
import com.mchange.v2.c3p0.ComboPooledDataSource
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, JValue, JsonInput, jvalue2extractable, string2JsonInput}
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.scalactic.Bad
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable
import scala.collection.immutable.Map
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.parsing.json


class TestBusPolPostSearchRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/a1" + ":" + "a1tok"))
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "C5JWLOEP8Uav2R4ryemCCIKHuPGS4z")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestPolicySearchPost"
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/u1" + ":" + "u1pw"))
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  
  private val AWAITDURATION: Duration = 15.seconds
  
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestPolicySearchPost/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestPolicySearchPost",
             owner         = "TestPolicySearchPost/u1",
             publicKey     = "",
             token         = "$2a$10$2ElhDrDUXcFzvU63Gl3dWeGYsWYTqgaBxkthhhdwwWc2YTP1yB4Ky")
  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
           description        = "",
           label              = "",
           lastUpdated        = ApiTime.nowUTC,
           orgId              = "TestPolicySearchPost",
           orgType            = "",
           tags               = None)
  private val TESTPOLICIES: Seq[BusinessPolicyRow] =
    Seq(BusinessPolicyRow(businessPolicy = "TestPolicySearchPost/pol1",
                          constraints    = """["a == b"]""",
                          created        = ApiTime.nowUTC,
                          description    = "",
                          label          = "pol1",
                          lastUpdated    = ApiTime.nowUTC,
                          orgid          = "TestPolicySearchPost",
                          owner          = "TestPolicySearchPost/u1",
                          properties     = """[{"name":"purpose","value":"location"}]""",
                          service        = """{"name":"svc1","org":"TestPolicySearchPost","arch":"arm","serviceVersions":[{"version":"1.0.0"}],"nodeHealth":{"missing_heartbeat_interval":1800,"check_agreement_status":1800}}""",
                          userInput      = ""))
  private val TESTSERVICES: Seq[ServiceRow] =
    Seq(ServiceRow(arch                       = "arm",
                   clusterDeployment          = "",
                   clusterDeploymentSignature = "",
                   deployment                 = "",
                   deploymentSignature        = "",
                   description                = "",
                   documentation              = "",
                   imageStore                 = "",
                   label                      = "",
                   lastUpdated                = ApiTime.nowUTC,
                   matchHardware              = "",
                   orgid                      = "TestPolicySearchPost",
                   owner                      = "TestPolicySearchPost/u1",
                   public                     = false,
                   requiredServices           = "",
                   service                    = "TestPolicySearchPost/svc1_1.0.0_arm",
                   sharable                   = "multiple",
                   url                        = "svc1",
                   userInput                  = "",
                   version                    = "1.0.0"))
  private val TESTUSER: UserRow =
    UserRow(admin       = false,
            email       = "",
            hashedPw    = "",
            lastUpdated = ApiTime.nowUTC,
            orgid       = "TestPolicySearchPost",
            updatedBy   = "",
            username    = "TestPolicySearchPost/u1")
  
  implicit private val formats: Formats = DefaultFormats.withLong
  
  
  override def beforeAll() {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ.rows += TESTORGANIZATION) andThen
                                       (UsersTQ.rows += TESTUSER) andThen
                                       (AgbotsTQ.rows += TESTAGBOT) andThen
                                       (ServicesTQ.rows ++= TESTSERVICES)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.rows.filter(_.orgId startsWith "TestPolicySearchPost").delete andThen
                                       OrgsTQ.rows.filter(_.orgid startsWith "TestPolicySearchPost").delete), AWAITDURATION)
    
    DBCONNECTION.getDb.close()
  }
  
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.dropAllOffsets()), AWAITDURATION)
  }
  
  
  def fixtureNodeAgreements(testCode: Seq[NodeAgreementRow] ⇒ Any, testData: Seq[NodeAgreementRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(NodeAgreementsTQ.rows ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodeAgreementsTQ.rows.filter(_.agId inSet testData.map(_.agId)).delete), AWAITDURATION)
  }
  
  def fixtureNodes(testCode: Seq[NodeRow] ⇒ Any, testData: Seq[NodeRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(NodesTQ.rows ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodesTQ.rows.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  def fixtureOrganizations(testCode: Seq[OrgRow] ⇒ Any, testData: Seq[OrgRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(OrgsTQ.rows ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(OrgsTQ.rows.filter(_.orgid inSet testData.map(_.orgId)).delete), AWAITDURATION)
  }
  
  def fixturePagination(testCode: Seq[SearchOffsetPolicyAttributes] ⇒ Any, testData: Seq[SearchOffsetPolicyAttributes]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.offsets ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      None
  }
  
  def fixturePolicies(testCode: Seq[BusinessPolicyRow] ⇒ Any, testData: Seq[BusinessPolicyRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(BusinessPoliciesTQ.rows ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(BusinessPoliciesTQ.rows.filter(_.businessPolicy inSet testData.map(_.businessPolicy)).delete), AWAITDURATION)
  }
  
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- 400 Bad Request - changeSince < 0L") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(-1L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- 400 Bad Request - numEntries < 0") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, Some(-1), 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- No Nodes") {
    fixturePolicies(_ ⇒ {
      val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
      info("code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)
  
      val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
      assert(responseBody.nodes.isEmpty)
      assert(responseBody.offsetUpdated === false)
  
      val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
      assert(offset.nonEmpty)
      assert(offset.head._1.isEmpty)
      assert(offset.head._2 === 0L)
    }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- 409 Bad Session - Agbot Session Desynchronization") {
    fixturePolicies(_ ⇒ {
      Await.ready(DBCONNECTION.getDb.run(
        SearchOffsetPolicyTQ.offsets +=
          tables.SearchOffsetPolicyAttributes(agbot = "TestPolicySearchPost/a1",
                                              offset = None,
                                              policy = "TestPolicySearchPost/pol1",
                                              session = 1L)
      ), AWAITDURATION)
      
      val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
      info("code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ALREADY_EXISTS2.intValue)
  
      val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
      assert(responseBody.nodes.isEmpty)
      assert(responseBody.offsetUpdated === false)
  
      val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
      assert(offset.nonEmpty)
      assert(offset.head._1.isEmpty)
      assert(offset.head._2 === 1L)
    }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Matched Service Architecture") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            
            val nodes: Seq[BusinessPolicyNodeResponse] = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes
            assert(nodes.length === 1)
            assert(nodes(0).id === "TestPolicySearchPost/n1")
            assert(nodes(0).nodeType === "device")
            assert(nodes(0).publicKey === "key")
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Matched Service Architecture") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.NOT_FOUND.intValue)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Matched ALL Service Architectures - *") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
        orgid = "TestPolicySearchPost",
        token = "",
        name = "",
        owner = "TestPolicySearchPost/u1",
        nodeType = "device",
        pattern = "",
        regServices = "[]",
        userInput = "",
        msgEndPoint = "",
        softwareVersions = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        publicKey = "key",
        arch = "arm",
        heartbeatIntervals = "",
        lastUpdated = ApiTime.nowUTC))
    val TESTPOLICY: Seq[BusinessPolicyRow] =
      Seq(BusinessPolicyRow(businessPolicy = "TestPolicySearchPost/pol1",
                            constraints    = """["a == b"]""",
                            created        = ApiTime.nowUTC,
                            description    = "",
                            label          = "pol1",
                            lastUpdated    = ApiTime.nowUTC,
                            orgid          = "TestPolicySearchPost",
                            owner          = "TestPolicySearchPost/u1",
                            properties     = """[{"name":"purpose","value":"location"}]""",
                            service        = """{"name":"svc1","org":"TestPolicySearchPost","arch":"*","serviceVersions":[{"version":"1.0.0"}],"nodeHealth":{"missing_heartbeat_interval":1800,"check_agreement_status":1800}}""",
                            userInput      = ""))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            
            val nodes: Seq[BusinessPolicyNodeResponse] = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes
            assert(nodes.length === 1)
            assert(nodes(0).id === "TestPolicySearchPost/n1")
            assert(nodes(0).nodeType === "device")
            assert(nodes(0).publicKey === "key")
          }, TESTNODE)
      }, TESTPOLICY)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Matched ALL Service Architectures - Unspecified Architecture") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
        orgid = "TestPolicySearchPost",
        token = "",
        name = "",
        owner = "TestPolicySearchPost/u1",
        nodeType = "device",
        pattern = "",
        regServices = "[]",
        userInput = "",
        msgEndPoint = "",
        softwareVersions = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        publicKey = "key",
        arch = "arm",
        heartbeatIntervals = "",
        lastUpdated = ApiTime.nowUTC))
    val TESTPOLICY: Seq[BusinessPolicyRow] =
      Seq(BusinessPolicyRow(businessPolicy = "TestPolicySearchPost/pol1",
                            constraints    = """["a == b"]""",
                            created        = ApiTime.nowUTC,
                            description    = "",
                            label          = "pol1",
                            lastUpdated    = ApiTime.nowUTC,
                            orgid          = "TestPolicySearchPost",
                            owner          = "TestPolicySearchPost/u1",
                            properties     = """[{"name":"purpose","value":"location"}]""",
                            service        = """{"name":"svc1","org":"TestPolicySearchPost","arch":"","serviceVersions":[{"version":"1.0.0"}],"nodeHealth":{"missing_heartbeat_interval":1800,"check_agreement_status":1800}}""",
                            userInput      = ""))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            
            val nodes: Seq[BusinessPolicyNodeResponse] = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes
            assert(nodes.length === 1)
            assert(nodes(0).id === "TestPolicySearchPost/n1")
            assert(nodes(0).nodeType === "device")
            assert(nodes(0).publicKey === "key")
          }, TESTNODE)
      }, TESTPOLICY)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Node Type") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
        orgid = "TestPolicySearchPost",
        token = "",
        name = "",
        owner = "TestPolicySearchPost/u1",
        nodeType = "",
        pattern = "",
        regServices = "[]",
        userInput = "",
        msgEndPoint = "",
        softwareVersions = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        publicKey = "key",
        arch = "arm",
        heartbeatIntervals = "",
        lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            
            val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
            assert(NODE.id === "TestPolicySearchPost/n1")
            assert(NODE.nodeType === "device")
            assert(NODE.publicKey === "key")
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Public Key") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.NOT_FOUND.intValue)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Heartbeat") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "pattern",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = None,
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.NOT_FOUND.intValue)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Stale") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(_ ⇒ {
      fixtureNodes(_ ⇒ {
        val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(ApiTime.nowSeconds, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        assert(response.code === HttpCode.NOT_FOUND.intValue)
        
        val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
        assert(responseBody.nodes.isEmpty)
        assert(responseBody.offsetUpdated === false)
        
        val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
        assert(offset.nonEmpty)
        assert(offset.head._1.isEmpty)
        assert(offset.head._2 === 0L)
      }, TESTNODE)
    }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Matched Policy Organization") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost2/n1",
                  orgid = "TestPolicySearchPost2",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTORGANIZATION: Seq[OrgRow] =
      Seq(OrgRow(description = "",
                 heartbeatIntervals = "",
                 label = "",
                 lastUpdated = "",
                 orgId = "TestPolicySearchPost2",
                 orgType = "",
                 tags = None))
    
    fixtureOrganizations(
      _ ⇒ {
        fixturePolicies(
          _ ⇒ {
            fixtureNodes(
              _ ⇒ {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.NOT_FOUND.intValue)
              }, TESTNODE)
          }, TESTPOLICIES)
      }, TESTORGANIZATION)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Matched Policy Organization - With Request") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost2/n1",
        orgid = "TestPolicySearchPost2",
        token = "",
        name = "",
        owner = "TestPolicySearchPost/u1",
        nodeType = "device",
        pattern = "",
        regServices = "[]",
        userInput = "",
        msgEndPoint = "",
        softwareVersions = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        publicKey = "key",
        arch = "arm",
        heartbeatIntervals = "",
        lastUpdated = ApiTime.nowUTC))
    val TESTORGANIZATION: Seq[OrgRow] =
      Seq(OrgRow(description = "",
        heartbeatIntervals = "",
        label = "",
        lastUpdated = "",
        orgId = "TestPolicySearchPost2",
        orgType = "",
        tags = None))
    
    fixtureOrganizations(
      _ ⇒ {
        fixturePolicies(
          _ ⇒ {
            fixtureNodes(
              _ ⇒ {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, Some(List("TestPolicySearchPost2")), None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost2/n1")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODE)
          }, TESTPOLICIES)
      }, TESTORGANIZATION)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - Matched Node") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
                           nodeId = "TestPolicySearchPost/n1",
                           services = "",
                           agrSvcOrgid = "",
                           agrSvcPattern = "",
                           agrSvcUrl = "TestPolicySearchPost/svc1",
                           state = "signed",
                           lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            fixtureNodeAgreements(
              _ ⇒ {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.NOT_FOUND.intValue)
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - Mismatched Node") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC),
        NodeRow(id = "TestPolicySearchPost/n2",
                orgid = "TestPolicySearchPost",
                token = "",
                name = "",
                owner = "TestPolicySearchPost/u1",
                nodeType = "device",
                pattern = "",
                regServices = "[]",
                userInput = "",
                msgEndPoint = "",
                softwareVersions = "",
                lastHeartbeat = Some(ApiTime.nowUTC),
                publicKey = "key",
                arch = "arm",
                heartbeatIntervals = "",
                lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
                           nodeId = "TestPolicySearchPost/n1",
                           services = "",
                           agrSvcOrgid = "",
                           agrSvcPattern = "",
                           agrSvcUrl = "TestPolicySearchPost/svc1",
                           state = "signed",
                           lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            fixtureNodeAgreements(
              _ ⇒ {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                assert(parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.length === 1)
                
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost/n2")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - No Matched Node - Mismatched Service URL") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
        orgid = "TestPolicySearchPost",
        token = "",
        name = "",
        owner = "TestPolicySearchPost/u1",
        nodeType = "device",
        pattern = "",
        regServices = "[]",
        userInput = "",
        msgEndPoint = "",
        softwareVersions = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        publicKey = "key",
        arch = "arm",
        heartbeatIntervals = "",
        lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
        nodeId = "TestPolicySearchPost/n1",
        services = "",
        agrSvcOrgid = "",
        agrSvcPattern = "",
        agrSvcUrl = "url",
        state = "signed",
        lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            fixtureNodeAgreements(
              _ ⇒ {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost/n1")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - No Matched Node - No Serivce URL") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
                           nodeId = "TestPolicySearchPost/n1",
                           services = "",
                           agrSvcOrgid = "",
                           agrSvcPattern = "",
                           agrSvcUrl = "",
                           state = "signed",
                           lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            fixtureNodeAgreements(
              _ ⇒ {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
  
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost/n1")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - No Matched Node - No Agreement State") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
        orgid = "TestPolicySearchPost",
        token = "",
        name = "",
        owner = "TestPolicySearchPost/u1",
        nodeType = "device",
        pattern = "",
        regServices = "[]",
        userInput = "",
        msgEndPoint = "",
        softwareVersions = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        publicKey = "key",
        arch = "arm",
        heartbeatIntervals = "",
        lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
        nodeId = "TestPolicySearchPost/n1",
        services = "",
        agrSvcOrgid = "",
        agrSvcPattern = "",
        agrSvcUrl = "TestPolicySearchPost/svc1",
        state = "",
        lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            fixtureNodeAgreements(
              _ ⇒ {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost/n1")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Pagination - Less Nodes Than Available") {
    val UPDATEDTIME: String = ApiTime.nowUTC
    val TESTNODES: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = UPDATEDTIME),
          NodeRow(id = "TestPolicySearchPost/n2",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ ⇒ {
        fixtureNodes(
          _ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, Some(1), 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
      
            val RESPONSEBODY: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
            assert(RESPONSEBODY.nodes.length === 1)
            assert(RESPONSEBODY.offsetUpdated === true)
            assert(RESPONSEBODY.nodes.head.id === "TestPolicySearchPost/n1")
      
            val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
            assert(offset.nonEmpty)
            assert(offset.head._1 === Some(UPDATEDTIME))
            assert(offset.head._2 === 0L)
          }, TESTNODES)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Pagination - Increment Offset") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTPAGINATION: Seq[SearchOffsetPolicyAttributes] =
      Seq(SearchOffsetPolicyAttributes(agbot = "TestPolicySearchPost/a1",
                                       offset = Some(ApiTime.beginningUTC),
                                       policy = "TestPolicySearchPost/pol1",
                                       session = 0L))
  
    fixturePolicies(
      _ ⇒ {
        fixturePagination(
          _ ⇒ {
            fixtureNodes(
              _ ⇒ {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("code: " + response.code)
                info("body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
              
                val RESPONSEBODY: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
                assert(RESPONSEBODY.nodes.length === 1)
                assert(RESPONSEBODY.offsetUpdated === true)
                assert(RESPONSEBODY.nodes.head.id === "TestPolicySearchPost/n1")
              
                val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
                assert(offset.nonEmpty)
                assert(offset.head._1 === TESTNODE(0).lastHeartbeat)
                assert(offset.head._2 === 0L)
              }, TESTNODE)
          }, TESTPAGINATION)
     }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Pagination - Increment Session") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
        orgid = "TestPolicySearchPost",
        token = "",
        name = "",
        owner = "TestPolicySearchPost/u1",
        nodeType = "device",
        pattern = "",
        regServices = "[]",
        userInput = "",
        msgEndPoint = "",
        softwareVersions = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        publicKey = "key",
        arch = "arm",
        heartbeatIntervals = "",
        lastUpdated = ApiTime.nowUTC))
    val TESTPAGINATION: Seq[SearchOffsetPolicyAttributes] =
      Seq(SearchOffsetPolicyAttributes(agbot = "TestPolicySearchPost/a1",
                                       offset = Some(ApiTime.nowUTC),
                                       policy = "TestPolicySearchPost/pol1",
                                       session = 0L))
    
    fixturePolicies(
      _ ⇒ {
        fixturePagination(
          _ ⇒ {
            fixtureNodes(
              _ ⇒ {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 1L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("code: " + response.code)
                info("body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                val RESPONSEBODY: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
                assert(RESPONSEBODY.nodes.length === 1)
                assert(RESPONSEBODY.offsetUpdated === false)
                assert(RESPONSEBODY.nodes.head.id === "TestPolicySearchPost/n1")
                
                val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
                assert(offset.nonEmpty)
                assert(offset.head._1 === TESTPAGINATION(0).offset)
                assert(offset.head._2 === 1L)
              }, TESTNODE)
          }, TESTPAGINATION)
      }, TESTPOLICIES)
  }
}
