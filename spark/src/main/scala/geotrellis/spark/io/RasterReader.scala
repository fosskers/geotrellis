/*
 * Copyright 2016 Azavea
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

package geotrellis.spark.io

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.io.geotiff._
import geotrellis.raster.io.geotiff.reader.GeoTiffReader
import geotrellis.raster.rasterize.Rasterizer
import geotrellis.spark._
import geotrellis.util.{ByteReader, StreamingByteReader}
import geotrellis.vector._

import spire.syntax.cfor._

import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, ZonedDateTime}


/**
  * Type class to read a raster either fully or partially from a ByteReader.
  * This abstracts over the different ways to represent a GeoTiff values and different ways to key it.
  *
  * Option object is a type parameter such that novel ways of GeoTiff parsing can be provided by the user.
  *
  * @tparam O Options type that is used to configure the raster reading
  * @tparam R Result of reading the raster bytes either fully or as a pixel window
  */
trait RasterReader[-O, R] extends Serializable {
  def readFully(byteReader: ByteReader, options: O): R
  def readWindow(byteReader: StreamingByteReader, pixelWindow: GridBounds, options: O): R
  def readWindows(gbs: Array[GridBounds], info: GeoTiffReader.GeoTiffInfo, options: O): Iterator[R]
}

object RasterReader {

  trait Options {
    def crs: Option[CRS]
    def timeTag: String
    def timeFormat: String

    lazy val timeFormatter = DateTimeFormatter.ofPattern(timeFormat).withZone(ZoneOffset.UTC)

    def parseTime(tags: Tags): ZonedDateTime = {
      val dateTimeString = tags.headTags.getOrElse(timeTag, sys.error(s"There is no tag $timeTag in the GeoTiff header"))
      ZonedDateTime.from(timeFormatter.parse(dateTimeString))
    }
  }

  private def best(maxSize: Int, segment: Int): Int = {
    var i: Int = 1
    var result: Int = -1
    // Search for the largest factor of segment that is > 1 and <=
    // maxSize.  If one cannot be found, give up and return maxSize.
    while (i < math.sqrt(segment) && result == -1) {
      if ((segment % i == 0) && ((segment/i) <= maxSize)) result = (segment/i)
      i += 1
    }
    if (result == -1) maxSize; else result
  }

  def listWindows(
    cols: Int, rows: Int, maxSize: Int,
    segCols: Int, segRows: Int
  ): Array[GridBounds] = {
    val colSize: Int =
      if (maxSize >= segCols * 2) {
        math.floor(maxSize.toDouble / segCols).toInt * segCols
      } else if (maxSize >= segCols) {
        segCols
      } else best(maxSize, segCols)

    val rowSize: Int =
      if (maxSize >= segRows * 2) {
        math.floor(maxSize.toDouble / segRows).toInt * segRows
      } else if (maxSize >= segRows) {
        segRows
      } else best(maxSize, segRows)

    val windows = listWindows(cols, rows, colSize, rowSize)

    windows
  }

  /** List all pixel windows that meet the given geometry */
  def listWindows(
    cols: Int, rows: Int, maxSize: Int,
    extent: Extent, segCols: Int, segRows: Int, geometry: Geometry,
    options: Rasterizer.Options = Rasterizer.Options.DEFAULT
  ): Array[GridBounds] = {
    val maxColSize: Int =
      if (maxSize >= segCols * 2) {
        math.floor(maxSize.toDouble / segCols).toInt * segCols
      } else if (maxSize >= segCols) {
        segCols
      } else best(maxSize, segCols)

    val maxRowSize: Int =
      if (maxSize >= segRows) {
        math.floor(maxSize.toDouble / segRows).toInt * segRows
      } else if (maxSize >= segRows) {
        segRows
      } else best(maxSize, segRows)

    val result = scala.collection.mutable.ArrayBuffer[GridBounds]()
    val re = RasterExtent(extent, math.max(cols/maxColSize,1), math.max(rows/maxRowSize,1))

    Rasterizer.foreachCellByGeometry(geometry, re, options)({ (col: Int, row: Int) =>
      result +=
      GridBounds(
        col * maxColSize,
        row * maxRowSize,
        math.min((col+1)*maxColSize - 1, cols-1),
        math.min((row+1)*maxRowSize - 1, rows-1)
      )
    })
    result.toArray
  }

  /** List all pixel windows that cover a grid of given size */
  def listWindows(cols: Int, rows: Int, colSize: Int, rowSize: Int): Array[GridBounds] = {
    val result = scala.collection.mutable.ArrayBuffer[GridBounds]()
    cfor(0)(_ < cols, _ + colSize) { col =>
      cfor(0)(_ < rows, _ + rowSize) { row =>
        result +=
        GridBounds(
          col,
          row,
          math.min(col + colSize - 1, cols - 1),
          math.min(row + rowSize - 1, rows - 1)
        )
      }
    }
    result.toArray
  }

  /** List all pixel windows that cover a grid of given size */
  def listWindows(cols: Int, rows: Int, maxTileSize: Option[Int]): Array[GridBounds] = {
    val result = scala.collection.mutable.ArrayBuffer[GridBounds]()
    maxTileSize match {
      case Some(tileSize) =>
        cfor(0)(_ < cols, _ + tileSize) { col =>
          cfor(0)(_ < rows, _ + tileSize) { row =>
            result +=
              GridBounds(
                col,
                row,
                math.min(col + tileSize - 1, cols - 1),
                math.min(row + tileSize - 1, rows - 1)
              )
          }
        }
      case None =>
        result += GridBounds(0, 0, cols - 1, rows - 1)
    }
    result.toArray
  }

  implicit def singlebandGeoTiffReader = new RasterReader[Options, (ProjectedExtent, Tile)] {
    def readFully(byteReader: ByteReader, options: Options) = {
      val geotiff = SinglebandGeoTiff(byteReader)
      val raster: Raster[Tile] = geotiff.raster
      (ProjectedExtent(raster.extent, options.crs.getOrElse(geotiff.crs)), raster.tile)
    }

    def readWindow(streamingByteReader: StreamingByteReader, pixelWindow: GridBounds, options: Options) = {
      val geotiff = SinglebandGeoTiff.streaming(streamingByteReader)
      val raster: Raster[Tile] = geotiff.raster.crop(pixelWindow)
      (ProjectedExtent(raster.extent, options.crs.getOrElse(geotiff.crs)), raster.tile)
    }

    def readWindows(gbs: Array[GridBounds], info: GeoTiffReader.GeoTiffInfo, options: Options) = {
      val geoTiff = GeoTiffReader.geoTiffSinglebandTile(info)
      val gridBounds = geoTiff.gridBounds
      gbs
        .filter(gridBounds.contains)
        .map { gb => (ProjectedExtent(info.mapTransform(gb), options.crs.getOrElse(info.crs)), geoTiff.crop(gb)) }
        .toIterator
    }
  }

  implicit def multibandGeoTiffReader = new RasterReader[Options, (ProjectedExtent, MultibandTile)] {
    def readFully(byteReader: ByteReader, options: Options) = {
      val geotiff = MultibandGeoTiff(byteReader)
      val raster: Raster[MultibandTile] = geotiff.raster
      (ProjectedExtent(raster.extent, options.crs.getOrElse(geotiff.crs)), raster.tile)
    }

    def readWindow(streamingByteReader: StreamingByteReader, pixelWindow: GridBounds, options: Options) = {
      val geotiff = MultibandGeoTiff.streaming(streamingByteReader)
      val raster: Raster[MultibandTile] = geotiff.raster.crop(pixelWindow)
      (ProjectedExtent(raster.extent, options.crs.getOrElse(geotiff.crs)), raster.tile)
    }

    def readWindows(gbs: Array[GridBounds], info: GeoTiffReader.GeoTiffInfo, options: Options) = {
      val geoTiff = GeoTiffReader.geoTiffMultibandTile(info)
      val gridBounds = geoTiff.gridBounds
      gbs
        .filter(gridBounds.contains)
        .map { gb => (ProjectedExtent(info.mapTransform(gb), options.crs.getOrElse(info.crs)), geoTiff.crop(gb)) }
        .toIterator
    }
  }

  implicit def temporalSinglebandGeoTiffReader = new RasterReader[Options, (TemporalProjectedExtent, Tile)]  {
    def readFully(byteReader: ByteReader, options: Options) = {
      val geotiff = SinglebandGeoTiff(byteReader)
      val raster: Raster[Tile] = geotiff.raster
      val time = options.parseTime(geotiff.tags)
      val crs = options.crs.getOrElse(geotiff.crs)
      (TemporalProjectedExtent(raster.extent, crs, time), raster.tile)
    }

    def readWindow(streamingByteReader: StreamingByteReader, pixelWindow: GridBounds, options: Options) = {
      val geotiff = SinglebandGeoTiff.streaming(streamingByteReader)
      val raster: Raster[Tile] = geotiff.raster.crop(pixelWindow)
      val time = options.parseTime(geotiff.tags)
      val crs = options.crs.getOrElse(geotiff.crs)
      (TemporalProjectedExtent(raster.extent, crs, time), raster.tile)
    }

    def readWindows(gbs: Array[GridBounds], info: GeoTiffReader.GeoTiffInfo, options: Options) = {
      val geoTiff = GeoTiffReader.geoTiffSinglebandTile(info)
      val gridBounds = geoTiff.gridBounds
      gbs
        .filter(gridBounds.contains)
        .map { gb =>
          (TemporalProjectedExtent(
            info.mapTransform(gb),
            crs = options.crs.getOrElse(info.crs),
            options.parseTime(info.tags)), geoTiff.crop(gb))
        }
        .toIterator
    }
  }

  implicit def temporalMultibandGeoTiffReader = new RasterReader[Options, (TemporalProjectedExtent, MultibandTile)]  {
    def readFully(byteReader: ByteReader, options: Options) = {
      val geotiff = MultibandGeoTiff(byteReader)
      val raster: Raster[MultibandTile] = geotiff.raster
      val time = options.parseTime(geotiff.tags)
      val crs = options.crs.getOrElse(geotiff.crs)
      (TemporalProjectedExtent(raster.extent, crs, time), raster.tile)
    }

    def readWindow(streamingByteReader: StreamingByteReader, pixelWindow: GridBounds, options: Options) = {
      val geotiff = MultibandGeoTiff.streaming(streamingByteReader)
      val raster: Raster[MultibandTile] = geotiff.raster.crop(pixelWindow)
      val time = options.parseTime(geotiff.tags)
      val crs = options.crs.getOrElse(geotiff.crs)
      (TemporalProjectedExtent(raster.extent, crs, time), raster.tile)
    }

    def readWindows(gbs: Array[GridBounds], info: GeoTiffReader.GeoTiffInfo, options: Options) = {
      val geoTiff = GeoTiffReader.geoTiffMultibandTile(info)
      val gridBounds = geoTiff.gridBounds
      gbs
        .filter(gridBounds.contains)
        .map { gb =>
          (TemporalProjectedExtent(
            info.mapTransform(gb),
            options.crs.getOrElse(info.crs),
            options.parseTime(info.tags)), geoTiff.crop(gb))
        }
        .toIterator
    }
  }
}
