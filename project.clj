(defproject com.github.redhatqe/polarizer-umb "0.2.0-SNAPSHOT"
  :description "A small JMS/ActiveMQ helper library for Polarion"
  :url "https://github.com/RedHatQE/polarizer-umb"
  :java-source-path "src"
  :java-source-paths ["src"]
  :dependencies [
    [com.fasterxml.jackson.dataformat/jackson-dataformat-yaml "2.9.2"]
    [org.apache.activemq/activemq-all "5.15.2"]
    [org.apache.commons/commons-collections4 "4.1"]
    [com.github.redhatqe.polarizer/reporter "0.2.3-SNAPSHOT"]
    [io.reactivex.rxjava2/rxjava "2.1.13"]]
  :javac-options {:debug "on"}
  :plugins [[lein2-eclipse "2.0.0"]])
