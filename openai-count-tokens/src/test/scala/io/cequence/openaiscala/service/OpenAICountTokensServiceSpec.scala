package io.cequence.openaiscala.service

import akka.testkit.TestKit
import io.cequence.openaiscala.domain.{ChatRole, FunMessageSpec, FunctionSpec, MessageSpec}
import io.cequence.openaiscala.domain.settings.CreateChatCompletionSettings
import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll

import scala.collection.immutable.ListMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class OpenAICountTokensServiceSpec
    extends TestKit(ActorSystem("OpenAICountTokensServiceSpec"))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar
    with ScalaFutures {

  protected implicit val patience: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  trait TestCase {
    protected lazy val config: Config = ConfigFactory.load()
    private val verbose = false
    private lazy val validateWithChatGPT = config.getBoolean("validateWithChatGPT")
    protected lazy val sut: OpenAICountTokensService = new OpenAICountTokensService()
    protected lazy val openAIServiceExternal: OpenAIService = OpenAIServiceFactory(config)

    private def print(
      expectedTokens: Int,
      countedTokens: Int,
      tokensFromOpenAI: Option[Int]
    ): Unit = {
      if (verbose) {
        if (tokensFromOpenAI.isDefined) {
          println(
            "Test tokens counted: " + countedTokens + " tokens from OpenAI: " + tokensFromOpenAI + " expected: " + expectedTokens
          )
        } else {
          println(
            "Test tokens counted: " + countedTokens + " expected: " + expectedTokens
          )
        }
      } else ()
    }

    protected def checkTokensForMessageCall(
      messages: Seq[MessageSpec],
      usedTokens: Int,
      model: String
    ): Unit = {
      val countedTokens = sut.countMessageTokens(messages)
      if (validateWithChatGPT) {
        val tokensFromOpenAI = openAIServiceExternal
          .createChatCompletion(
            messages,
            CreateChatCompletionSettings(
              model = model,
              temperature = Some(0.0),
              max_tokens = Some(1)
            )
          )
          .futureValue
          .usage
          .get
          .prompt_tokens
        print(usedTokens, countedTokens, Some(tokensFromOpenAI))
        tokensFromOpenAI shouldEqual usedTokens
      } else {
        print(usedTokens, countedTokens, None)
      }
      countedTokens shouldEqual usedTokens
      ()
    }

    protected def checkTokensForFunctionCall(
      functions: Seq[FunctionSpec],
      messages: Seq[FunMessageSpec],
      usedTokens: Int,
      responseFunctionName: Option[String] = None,
      model: String = "gpt-3.5-turbo"
    ): Unit = {
      val countedTokens = sut.countFunMessageTokens(
        messages = messages,
        functions = functions,
        responseFunctionName = responseFunctionName
      )
      if (validateWithChatGPT) {
        val tokensFromOpenAI = openAIServiceExternal
          .createChatFunCompletion(
            messages = messages,
            functions = functions,
            settings = CreateChatCompletionSettings(
              model = model,
              temperature = Some(0.0),
              max_tokens = Some(1)
            ),
            responseFunctionName = responseFunctionName
          )
          .futureValue
          .usage
          .get
          .prompt_tokens
        print(usedTokens, countedTokens, Some(tokensFromOpenAI))
        tokensFromOpenAI shouldEqual usedTokens
      } else {
        print(usedTokens, countedTokens, None)
      }
      countedTokens shouldEqual usedTokens
      ()
    }
  }

  "countMessageTokens" should {
    "counting tokens - only messages - gpt-4" in new TestCase {
      val messages: Seq[MessageSpec] = Seq(MessageSpec(ChatRole.User, "hello", None))
      checkTokensForMessageCall(messages, usedTokens = 8, model = "gpt-4")
    }
    "counting tokens - only messages - gpt-3.5-turbo" in new TestCase {
      val messages: Seq[MessageSpec] = Seq(MessageSpec(ChatRole.User, "hello", None))
      checkTokensForMessageCall(messages, usedTokens = 8, model = "gpt-3.5-turbo")
    }
    "counting tokens - two messages - gpt-3.5-turbo" in new TestCase {
      val messages: Seq[MessageSpec] = Seq(
        MessageSpec(ChatRole.User, "hello", None),
        MessageSpec(ChatRole.Assistant, "hello", Some("name"))
      )
      checkTokensForMessageCall(messages, usedTokens = 14, model = "gpt-3.5-turbo")
    }
    "counting tokens - two messages - gpt-4" in new TestCase {
      val messages: Seq[MessageSpec] = Seq(
        MessageSpec(ChatRole.User, "hello", None),
        MessageSpec(ChatRole.Assistant, "hello world", Some("name"))
      )
      checkTokensForMessageCall(messages, usedTokens = 15, model = "gpt-4")
    }
  }

  "countFunMessageTokens" should {
    "counting tokens with function - nested description" in new TestCase {
      private val function1 = FunctionSpec(
        name = "function",
        description = Some("description"),
        parameters = Map(
          "type" -> "object",
          "properties" -> ListMap(
            "quality" -> ListMap(
              "type" -> "object",
              "properties" -> ListMap(
                "pros" -> ListMap(
                  "type" -> "array",
                  "description" -> "Write 3 points why this text is well written",
                  "items" -> ListMap("type" -> "string")
                )
              )
            )
          )
        )
      )
      val messages: Seq[FunMessageSpec] =
        Seq(FunMessageSpec(ChatRole.User, Some("hello"), None, None))
      checkTokensForFunctionCall(Seq(function1), messages, usedTokens = 46)
    }
    "counting tokens with function - required field" in new TestCase {
      private val function1 = FunctionSpec(
        name = "function",
        description = Some("description"),
        parameters = ListMap(
          "type" -> "object",
          "properties" -> ListMap(
            "title" -> ListMap("type" -> "string", "description" -> "Write something")
          ),
          "required" -> List("title")
        )
      )
      val messages: Seq[FunMessageSpec] =
        Seq(FunMessageSpec(ChatRole.User, Some("text1\ntext2\ntext3\n"), None, None))
      checkTokensForFunctionCall(Seq(function1), messages, usedTokens = 53)
    }
    "counting tokens with function - nested description, enums" in new TestCase {
      private val function1 = FunctionSpec(
        name = "function",
        description = Some("description1"),
        parameters = ListMap(
          "type" -> "object",
          "description" -> "description2",
          "properties" -> ListMap(
            "mainField" -> ListMap("type" -> "string", "description" -> "description3"),
            "field number one" -> ListMap(
              "type" -> "object",
              "description" -> "description4",
              "properties" -> ListMap(
                "yesNoField" -> ListMap(
                  "type" -> "string",
                  "description" -> "description5",
                  "enum" -> List("Yes", "No")
                ),
                "howIsInteresting" -> ListMap(
                  "type" -> "string",
                  "description" -> "description6"
                ),
                "scoreInteresting" -> ListMap(
                  "type" -> "number",
                  "description" -> "description7"
                ),
                "isInteresting" -> ListMap(
                  "type" -> "string",
                  "description" -> "description8",
                  "enum" -> List("Yes", "No")
                )
              )
            )
          )
        )
      )
      val messages: Seq[FunMessageSpec] =
        Seq(FunMessageSpec(ChatRole.User, Some("hello"), None, None))
      checkTokensForFunctionCall(Seq(function1), messages, usedTokens = 93)
    }
    "counting tokens with function - two fields in object" in new TestCase {
      private val function1 = FunctionSpec(
        name = "get_recipe",
        parameters = ListMap(
          "type" -> "object",
          "required" -> List("ingredients", "instructions", "time_to_cook"),
          "properties" -> ListMap(
            "ingredients" -> ListMap(
              "type" -> "array",
              "items" -> ListMap(
                "type" -> "object",
                "required" -> List("name", "unit", "amount"),
                "properties" -> ListMap(
                  "name" -> ListMap("type" -> "string"),
                  "unit" -> ListMap(
                    "enum" -> List("grams", "ml", "cups", "pieces", "teaspoons"),
                    "type" -> "string"
                  ),
                  "amount" -> ListMap("type" -> "number")
                )
              )
            ),
            "instructions" -> ListMap(
              "type" -> "array",
              "items" -> ListMap("type" -> "string"),
              "description" -> "Steps to prepare the recipe (no numbering)"
            ),
            "time_to_cook" -> ListMap(
              "type" -> "number",
              "description" -> "Total time to prepare the recipe in minutes"
            )
          )
        )
      )
      val messages: Seq[FunMessageSpec] =
        Seq(FunMessageSpec(ChatRole.User, Some("hello"), None, None))
      checkTokensForFunctionCall(Seq(function1), messages, usedTokens = 106)
    }
    "counting tokens with function - many messages" in new TestCase {
      private val function1 = FunctionSpec(
        name = "do_stuff",
        parameters = ListMap("type" -> "object", "properties" -> ListMap())
      )
      val messages: Seq[FunMessageSpec] = Seq(
        FunMessageSpec(ChatRole.System, Some("Hello:"), None, None),
        FunMessageSpec(ChatRole.System, Some("Hello"), None, None),
        FunMessageSpec(ChatRole.User, Some("Hi there"), None, None)
      )
      checkTokensForFunctionCall(Seq(function1), messages, usedTokens = 40)
    }
    "counting tokens with function - empty properties in object" in new TestCase {
      private val function1 = FunctionSpec(
        name = "do_stuff",
        parameters = ListMap("type" -> "object", "properties" -> ListMap())
      )
      val messages: Seq[FunMessageSpec] =
        Seq(
          FunMessageSpec(ChatRole.System, Some("Hello:"), None, None),
          FunMessageSpec(ChatRole.User, Some("Hi there"), None, None)
        )
      checkTokensForFunctionCall(Seq(function1), messages, usedTokens = 35)
    }
    "counting tokens with function - gpt4 model" in new TestCase {
      private val function1 = FunctionSpec(
        name = "function",
        description = Some("description"),
        parameters = Map(
          "type" -> "object",
          "properties" -> ListMap(
            "quality" -> ListMap(
              "type" -> "object",
              "properties" -> ListMap(
                "pros" -> ListMap(
                  "type" -> "array",
                  "description" -> "Write 3 points why this text is well written",
                  "items" -> ListMap("type" -> "string")
                )
              )
            )
          )
        )
      )
      val messages: Seq[FunMessageSpec] =
        Seq(FunMessageSpec(ChatRole.User, Some("hello"), None, None))
      checkTokensForFunctionCall(Seq(function1), messages, usedTokens = 46, model = "gpt-4")
    }
    "counting tokens with function - responseFunctionName is set to Some" in new TestCase {
      private val function1 = FunctionSpec(
        name = "function",
        description = Some("description"),
        parameters = Map(
          "type" -> "object",
          "properties" -> ListMap(
            "quality" -> ListMap(
              "type" -> "object",
              "properties" -> ListMap(
                "pros" -> ListMap(
                  "type" -> "array",
                  "description" -> "Write 3 points why this text is well written",
                  "items" -> ListMap("type" -> "string")
                )
              )
            )
          )
        )
      )
      private val messages: Seq[FunMessageSpec] =
        Seq(FunMessageSpec(ChatRole.User, Some("hello"), None, None))
      private val model = "gpt-3.5-turbo"
      private val responseFunctionName = Some("function")
      checkTokensForFunctionCall(
        Seq(function1),
        messages,
        usedTokens = 51,
        responseFunctionName = responseFunctionName,
        model = model
      )
    }
  }
}
