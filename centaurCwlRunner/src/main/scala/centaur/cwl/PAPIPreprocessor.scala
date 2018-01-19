package centaur.cwl
import io.circe.{Json, Printer, yaml}

class PAPIPreprocessor(prefix: String) {
  
  private def prefixFiles(json: Json): Json = json.asObject match {
    case Some(obj) =>
      val asMap = obj.toMap
      val isFile = asMap.exists({
        case ("class", file) => file.asString.contains("File")
        case _ => false
      })
      if (isFile) {
        val newLocation = asMap.get("location").map(l => s"$prefix${l.asString.get}")
        val newPath = asMap.get("path").map(l => s"$prefix${l.asString.get}")
        
        val withNewLocation = newLocation
          .map(l => obj.add("location", Json.fromString(l))).getOrElse(obj)

        val withNewPath = newPath
          .map(p => withNewLocation.add("path", Json.fromString(p))).getOrElse(withNewLocation)

        Json.fromJsonObject(withNewPath)
          
      } else Json.fromJsonObject(obj.mapValues(j => prefixFiles(j)))
    case _ => json
  }
  
  def preProcessInput(input: String): String = {
    yaml.parser.parse(input) match {
      case Left(error) => throw new Exception(error.getMessage)
      case Right(json) => Printer.spaces2.pretty(prefixFiles(json))
    }
  }

}
