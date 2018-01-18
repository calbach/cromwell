task validate_int {
  Int i
  command {
    echo $(( ${i} % 2 ))
  }
  output {
    Boolean validation = read_int(stdout()) == 1
  }
  runtime {
    docker: "ubuntu:latest"
  }
}

task mirror {
  Int i
  command {
    echo ${i}
  }
  output {
    Int out = read_int(stdout())
  }
  runtime {
    docker: "ubuntu:latest"
  }
}

workflow ifs_in_scatters {
  Array[Int] numbers = range(5)
  call mirror as init { input: i = 1 }

  scatter (n in numbers) {

    call validate_int { input: i = n }
    if (validate_int.validation) {
      Int incremented = n + 1
      call mirror { input: i = incremented + init.out }
    }
  }

  output {
    Array[Int?] mirrors = mirror.out
  }
}
