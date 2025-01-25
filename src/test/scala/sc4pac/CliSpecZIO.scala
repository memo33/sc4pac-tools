// package io.github.memo33.sc4pac

// import zio.*
// import zio.test.*

// object CliSpecZIO extends ZIOSpecDefault {

//   def spec = suite("CliSpecZIO") {
//     val port: Int = scala.util.Random.between(50000, 60000)
//     test("server should time-out") {
//       for {
//         _  <- ZIO.attempt {  // TODO testing this requires circumventing System.exit
//                 cli.CliMain.main(Array[String](
//                   "server",
//                   "--profiles-dir", "target/test-profiles",
//                   "--auto-shutdown=true",
//                   "--port", port.toString,
//                 ))
//               }
//       } yield assertTrue(true)
//     }
//   }

// }
