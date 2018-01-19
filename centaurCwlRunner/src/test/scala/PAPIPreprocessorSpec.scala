import centaur.cwl.PAPIPreprocessor
import org.scalatest.{FlatSpec, Matchers}

class PAPIPreprocessorSpec extends FlatSpec with Matchers {
  behavior of "PAPIPreProcesser"
  
  it should "preprocess" in {
    val p = new PAPIPreprocessor("gs://bucket/")
    p.preProcessInput(
      """
        |{
        |  "irec": {
        |    "ifoo": {"location": "whale.txt", "class": "File"},
        |    "ibar": {"location": "ref.fasta", "class": "File"}
        |  }
        |}
        |
      """.stripMargin) shouldBe
      """{
        |  "irec" : {
        |    "ifoo" : {
        |      "location" : "gs://bucket/whale.txt",
        |      "class" : "File"
        |    },
        |    "ibar" : {
        |      "location" : "gs://bucket/ref.fasta",
        |      "class" : "File"
        |    }
        |  }
        |}""".stripMargin
  }
}
