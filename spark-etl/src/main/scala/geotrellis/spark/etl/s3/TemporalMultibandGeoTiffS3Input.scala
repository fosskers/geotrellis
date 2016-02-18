package geotrellis.spark.etl.s3

import geotrellis.raster.{MultiBandTile, Tile}
import geotrellis.spark._
import geotrellis.spark.io.s3._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

class TemporalMultibandGeoTiffS3Input extends S3Input[TemporalProjectedExtent, MultiBandTile] {
  val format = "temporal-geotiff"
  def apply(props: Parameters)(implicit sc: SparkContext): RDD[(TemporalProjectedExtent, MultiBandTile)] =
    sc.newAPIHadoopRDD(configuration(props), classOf[TemporalMultibandGeoTiffS3InputFormat], classOf[TemporalProjectedExtent], classOf[MultiBandTile])
}
