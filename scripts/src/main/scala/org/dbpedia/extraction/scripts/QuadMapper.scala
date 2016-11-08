package org.dbpedia.extraction.scripts

import java.lang.StringBuilder

import org.dbpedia.extraction.destinations.{Destination, Quad, WriterDestination}
import org.dbpedia.extraction.destinations.formatters.TerseFormatter
import org.dbpedia.extraction.destinations.formatters.UriPolicy.Policy
import org.dbpedia.extraction.util.IOUtils.writer
import org.dbpedia.extraction.util.StringUtils.formatCurrentTimestamp
import org.dbpedia.extraction.util.{DateFinder, FileLike, IOUtils, UriUtils}

import scala.Console.err

/**
 * Maps old quads/triples to new quads/triples.
 */
object QuadMapper {

  //use the same recorder as Reader
  def getRecorder = QuadReader.getRecorder

  /**
   * @deprecated use one of the map functions below 
   */
  @Deprecated
  def mapQuads[T <% FileLike[T]](finder: DateFinder[T], input: String, output: String)(map: Quad => Traversable[Quad]): Unit = {
    // auto only makes sense on the first call to finder.find(), afterwards the date is set
    val destination = new WriterDestination(() => writer(finder.byName(output, auto = false).get), new QuadMapperFormatter())
    mapQuads(finder.language.wikiCode, finder.byName(input, auto = false).get, destination, required = true)(map)
  }
  def mapQuads[T <% FileLike[T]](finder: DateFinder[T], input: String, output: String, auto: Boolean, required: Boolean)(map: Quad => Traversable[Quad]): Unit = {
    // auto only makes sense on the first call to finder.find(), afterwards the date is set
    val readFiles = finder.byName(input, auto = auto).get
    val destination = new WriterDestination(() => writer(finder.byName(output, auto).get), new QuadMapperFormatter())
    mapQuads(finder.language.wikiCode, readFiles, destination, required = required)(map)
  }
  def mapQuads[T <% FileLike[T]](finder: DateFinder[T], input: String, output: String, required: Boolean )(map: Quad => Traversable[Quad]): Unit = {
    // auto only makes sense on the first call to finder.find(), afterwards the date is set
    val destination = new WriterDestination(() => writer(finder.byName(output, auto = false).get), new QuadMapperFormatter())
    mapQuads(finder.language.wikiCode, finder.byName(input, auto = false).get, destination, required)(map)
  }
  def mapQuads[T <% FileLike[T]](finder: DateFinder[T], input: String, output: String, required: Boolean, quads: Boolean, turtle: Boolean, policies: Array[Policy])(map: Quad => Traversable[Quad]): Unit = {
    mapQuads(finder.language.wikiCode, finder.byName(input, auto = false).get, finder.byName(output, auto = false).get, required, quads, turtle, policies)(map)
  }

  /**
   * @deprecated use one of the map functions below
   */
  @Deprecated
  def mapQuads(tag: String, inFile: FileLike[_], outFile: FileLike[_], required: Boolean = true, append: Boolean = false)(map: Quad => Traversable[Quad]): Unit = {
    
    if (! inFile.exists) {
      if (required) throw new IllegalArgumentException(tag+": file "+inFile+" does not exist")
      err.println(tag+": WARNING - file "+inFile+" does not exist")
      return
    }

    err.println(tag+": writing "+outFile+" ...")
    var mapCount = 0
    val writer = IOUtils.writer(outFile, append)
    try {
      // copied from org.dbpedia.extraction.destinations.formatters.TerseFormatter.footer
      writer.write("# started "+formatCurrentTimestamp+"\n")
      QuadReader.readQuads(tag, inFile) { old =>
        for (quad <- map(old)) {
          writer.write(quadToString(quad))
          mapCount += 1
        }
      }
      // copied from org.dbpedia.extraction.destinations.formatters.TerseFormatter.header
      writer.write("# completed "+formatCurrentTimestamp+"\n")
    }
    finally writer.close()
    err.println(tag+": mapped "+mapCount+" quads")
  }

  /**
    * @deprecated don't use it any more!
    */
  @Deprecated
  private def quadToString(quad: Quad): String = {
    val sb = new StringBuilder
    sb append '<' append quad.subject append "> <" append quad.predicate append "> "
    if (quad.datatype == null) {
      sb append '<' append quad.value append "> "
    }
    else {
      sb append '"' append quad.value append '"'
      if (quad.datatype != "http://www.w3.org/2001/XMLSchema#string") sb append "^^<" append quad.datatype append "> "
      else if (quad.language != null) sb append '@' append quad.language append ' '
    }
    if (quad.context != null) sb append '<' append quad.context append "> "
    sb append ".\n"
    sb.toString
  }

  /**
   */
  def mapQuads(tag: String, inFile: FileLike[_], outFile: FileLike[_], required: Boolean, quads: Boolean, turtle: Boolean, policies: Array[Policy])(map: Quad => Traversable[Quad]): Unit = {
    err.println(tag+": writing "+outFile+" ...")
    val destination = new WriterDestination(() => writer(outFile), new QuadMapperFormatter(quads, turtle, policies))
    mapQuads(tag, inFile, destination, required)(map)
  }

  def mapQuads(tag: String, inFile: FileLike[_], destination: Destination, required: Boolean) (map: Quad => Traversable[Quad]): Unit = {
    mapQuads(tag, inFile, destination, required, closeWriter = true)(map)
  }

  /**
   * TODO: do we really want to open and close the destination here? Users may want to map quads
   * from multiple input files to one destination. On the other hand, if the input file doesn't
   * exist, we probably shouldn't open the destination at all, so it's ok that it's happening in
   * this method after checking the input file.
    * Chile: made closing optional, also WriteDestination can only open Writer one now
   */
  def mapQuads(tag: String, inFile: FileLike[_], destination: Destination, required: Boolean, closeWriter: Boolean)(map: Quad => Traversable[Quad]): Unit = {
    
    if (! inFile.exists) {
      if (required) throw new IllegalArgumentException(tag+": file "+inFile+" does not exist")
      err.println(tag+": WARNING - file "+inFile+" does not exist")
      return
    }

    var mapCount = 0
    destination.open()
    try {
      QuadReader.readQuads(tag, inFile) { old =>
        destination.write(map(old))
        mapCount += 1
      }
    }
    finally if(closeWriter) destination.close()
    err.println(tag+": mapped "+mapCount+" quads")
  }

  /**
    * TODO: do we really want to open and close the destination here? Users may want to map quads
    * from multiple input files to one destination. On the other hand, if the input file doesn't
    * exist, we probably shouldn't open the destination at all, so it's ok that it's happening in
    * this method after checking the input file.
    */
  def mapSortedQuads(tag: String, inFile: FileLike[_], destination: Destination, required: Boolean)(map: Traversable[Quad] => Traversable[Quad]): Unit = {

    if (! inFile.exists) {
      if (required) throw new IllegalArgumentException(tag+": file "+inFile+" does not exist")
      err.println(tag+": WARNING - file "+inFile+" does not exist")
      return
    }

    var mapCount = 0
    destination.open()
    try {
      QuadReader.readSortedQuads(tag, inFile) { old =>
        destination.write(map(old))
        mapCount += old.size
      }
    }
    finally destination.close()
    err.println(tag+": mapped "+mapCount+" quads")
  }

  class QuadMapperFormatter(quad: Boolean = true, turtle: Boolean = true, policies: Array[Policy]= null) extends TerseFormatter(quad, turtle, policies) {
    def this(formatter: TerseFormatter){
      this(formatter.quads, formatter.turtle, formatter.policies)
    }
    private var contextAdditions = Map[String, String]()

    def addContextAddition(paramName: String, paramValue: String): Unit ={
      val param = paramName.replaceAll("\\s", "").toLowerCase()
      contextAdditions += ( param -> UriUtils.encodeUriComponent(paramValue))
    }

    override def render(quad: Quad): String = {
      var context = quad.context
      for(add <- contextAdditions)
        if(context.indexOf("#") > 0)
          context += "&" + add._1 + "=" + add._2
        else
          context += "#" + add._1 + "=" + add._2
      super.render(quad.copy(context=context))
    }
  }
}
